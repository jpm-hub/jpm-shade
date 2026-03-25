package jpm_shade;

import org.objectweb.asm.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/**
 * Merges any number of JARs into one fat JAR.
 *
 * Rules:
 * - .class collisions -> auto-renamed with _shade suffix (repeated until
 * unique)
 * - non-.class / non-MANIFEST -> ERROR and stop. With -bypass: keep largest,
 * print warning.
 * - META-INF/MANIFEST.MF -> always regenerated fresh (only Main-Class if --main
 * is set)
 *
 * Dependency (Maven):
 * <dependency>
 * <groupId>org.ow2.asm</groupId>
 * <artifactId>asm</artifactId>
 * <version>9.6</version>
 * </dependency>
 *
 * Usage:
 * java JarRelocator [--main com.example.Main] [-bypass] <output.jar>
 * <input1.jar> [input2.jar ...]
 */
public class JpmShade {
	private static final String MANIFEST_PATH = "META-INF/MANIFEST.MF";

	// Auto-populated during merge: originalEntryName -> resolvedEntryName (.class
	// files)
	private final Map<String, String> relocations = new LinkedHashMap<>();

	private static boolean verbose = false;

	// -------------------------------------------------------------------------
	// merge()
	// -------------------------------------------------------------------------
	public void merge(List<Path> inputs, Path outputJar,
			String mainClass, boolean bypass, boolean v,
			List<KeepRule> keepRules) throws IOException {
		JpmShade.verbose = v;

		if (outputJar.getParent() != null)
			Files.createDirectories(outputJar.getParent());

		// ---------------------------------------------------------------
		// PASS 1: Scan all JARs.
		// - Build _shade relocation map for .class collisions.
		// - Detect non-.class / non-MANIFEST collisions.
		// - For each non-.class collision, record all candidates (path + bytes)
		// so we can pick the largest if -bypass is on.
		// ---------------------------------------------------------------
		relocations.clear();

		Set<String> seenClasses = new HashSet<>();

		// name -> list of (jarPath, bytes) for non-.class resources
		Map<String, List<Candidate>> resourceCandidates = new LinkedHashMap<>();

		List<String> shadeMessages = new ArrayList<>();
		List<String> collisionErrors = new ArrayList<>();

		// Tracks which JAR currently holds the canonical (un-shaded) name slot
		Map<String, Path> classOwner = new HashMap<>();

		for (Path inputPath : inputs) {
			boolean isJar = inputPath.toString().toLowerCase().endsWith(".jar");
			if (isJar) {
				try (JarInputStream in = new JarInputStream(
						new BufferedInputStream(Files.newInputStream(inputPath)))) {
					JarEntry entry;
					while ((entry = in.getNextJarEntry()) != null) {
						String name = entry.getName();
						byte[] bytes = in.readAllBytes();

						// Skip MANIFEST and all directory entries
						if (name.equals(MANIFEST_PATH) || name.endsWith("/"))
							continue;

						if (name.endsWith(".class")) {
							if (name.equals("module-info.class") || name.endsWith("/module-info.class")) {
								// Treat like a resource: collision -> error unless -bypass, then keep largest
								resourceCandidates
										.computeIfAbsent(name, k -> new ArrayList<>())
										.add(new Candidate(inputPath, bytes));
							} else if (!seenClasses.contains(name)) {
								seenClasses.add(name);
								relocations.putIfAbsent(name, name);
								classOwner.put(name, inputPath);
							} else {
								// Collision: check keep rules to decide which JAR gets canonical name
								boolean currentKept = keepRules.stream().anyMatch(r -> r.matches(inputPath, name));
								boolean ownerKept   = keepRules.stream().anyMatch(r -> r.matches(classOwner.get(name), name));

								if (currentKept && !ownerKept) {
									// Promote current JAR to canonical; demote previous owner to _shade
									Path prevOwner = classOwner.get(name);
									String resolved = addShadeSuffix(name);
									while (seenClasses.contains(resolved)) resolved = addShadeSuffix(resolved);
									seenClasses.add(resolved);
									relocations.put(name + "@" + prevOwner, resolved);
									classOwner.put(name, inputPath);
								if (verbose) shadeMessages.add("[SHADE] " + name
										+ " | kept: " + inputPath.getFileName() + " (--keep)"
										+ " | shaded: " + prevOwner.getFileName() + " -> " + resolved);
							} else {
								// Current JAR gets _shade (default)
								Path owner = classOwner.get(name);
								String resolved = addShadeSuffix(name);
								while (seenClasses.contains(resolved)) resolved = addShadeSuffix(resolved);
								seenClasses.add(resolved);
								relocations.put(name + "@" + inputPath, resolved);
								if (verbose) shadeMessages.add("[SHADE] " + name
										+ " | kept: " + owner.getFileName()
										+ " | shaded: " + inputPath.getFileName() + " -> " + resolved);
								}
							}
						} else {
							resourceCandidates
									.computeIfAbsent(name, k -> new ArrayList<>())
									.add(new Candidate(inputPath, bytes));
						}
					}
				}
			} else {
				// Plain file: add directly as a resource entry
				String name = inputPath.getFileName().toString();
				byte[] bytes = Files.readAllBytes(inputPath);
				resourceCandidates
						.computeIfAbsent(name, k -> new ArrayList<>())
						.add(new Candidate(inputPath, bytes));
			}
		}

		// ---------------------------------------------------------------
		// Check non-.class collisions
		// ---------------------------------------------------------------
		Map<String, byte[]> resolvedResources = new LinkedHashMap<>();
		List<String> warningMessages = new ArrayList<>();

		for (Map.Entry<String, List<Candidate>> e : resourceCandidates.entrySet()) {
			String name = e.getKey();
			List<Candidate> candidates = e.getValue();

			if (candidates.size() == 1) {
				resolvedResources.put(name, candidates.get(0).bytes);
			} else {
				StringBuilder sources = new StringBuilder();
				for (Candidate c : candidates) {
					sources.append("\n    ").append(c.jarPath.getFileName())
							.append(" (").append(c.bytes.length).append(" bytes)");
				}

				// --keep rule: if any candidate's JAR has a matching rule, it wins unconditionally
				Optional<Candidate> keptCandidate = candidates.stream()
						.filter(c -> keepRules.stream().anyMatch(r -> r.matches(c.jarPath, name)))
						.findFirst();
				if (keptCandidate.isPresent()) {
					Candidate kept = keptCandidate.get();
					boolean isModuleInfo = name.equals("module-info.class") || name.endsWith("/module-info.class");
					String label = isModuleInfo ? "[WARNING] module-info.class collision" : "[WARNING] Resource collision for \"" + name + "\"";
					if (verbose) warningMessages.add(label + ":" + sources
							+ "\n    -> Kept by --keep rule: " + kept.jarPath.getFileName());
					resolvedResources.put(name, kept.bytes);
				} else {
					boolean isModuleInfo = name.equals("module-info.class") || name.endsWith("/module-info.class");
					if (!bypass) {
						// Build per-collision --keep hints for each candidate
						StringBuilder solutions = new StringBuilder();
						String dotName = name.endsWith(".class")
								? name.substring(0, name.length() - 6).replace('/', '.')
								: name;
						for (Candidate c : candidates) {
								solutions.append("\n  Solution: --keep ")
										.append(c.jarPath.getFileName()).append(":").append(dotName);
							}
						String msg = isModuleInfo
								? "[ERROR] module-info.class collision (merging module-info.class is not yet supported):" + sources + solutions
								: "[ERROR] Resource collision for \"" + name + "\":\n" + sources + solutions;
						collisionErrors.add(msg);
					} else {
						Candidate largest = candidates.stream()
								.max(Comparator.comparingInt(c -> c.bytes.length))
								.orElseThrow();
						String label = isModuleInfo ? "[WARNING] module-info.class collision" : "[WARNING] Resource collision for \"" + name + "\"";
						if (verbose) warningMessages.add(label + ":" + sources
								+ "\n    -> Keeping largest: " + largest.jarPath.getFileName()
								+ " (" + largest.bytes.length + " bytes)");
						resolvedResources.put(name, largest.bytes);
					}
				}
			}
		}

		// Print all deferred shade/warning/error messages now
		shadeMessages.forEach(System.out::println);
		warningMessages.forEach(System.err::println);
		if (!collisionErrors.isEmpty()) {
			collisionErrors.forEach(System.err::println);
			System.err.println("\nAborted. Use --keep to pin a winner, or pass -bypass to keep the largest file.");
			System.exit(1);
		}

		// ---------------------------------------------------------------
		// PASS 2: Write everything
		// ---------------------------------------------------------------
		Set<String> writtenEntries = new HashSet<>();

		try (JarOutputStream out = new JarOutputStream(
				new BufferedOutputStream(Files.newOutputStream(outputJar)))) {

			// MANIFEST must be first entry per JAR spec
			writeManifest(out, mainClass);
			writtenEntries.add("META-INF/");
			writtenEntries.add(MANIFEST_PATH);

			// Write .class files
			for (Path inputPath : inputs) {
				if (!inputPath.toString().toLowerCase().endsWith(".jar")) continue;
				if (verbose) System.out.println("Processing: " + inputPath);
				try (JarInputStream in = new JarInputStream(
						new BufferedInputStream(Files.newInputStream(inputPath)))) {
					JarEntry entry;
					while ((entry = in.getNextJarEntry()) != null) {
						String originalName = entry.getName();
						byte[] bytes = in.readAllBytes();

						if (!originalName.endsWith(".class"))
							continue;

						String resolvedName = relocations.getOrDefault(
								originalName + "@" + inputPath,
								relocations.getOrDefault(originalName, originalName));

						if (writtenEntries.contains(resolvedName))
							continue;

						bytes = relocateClass(bytes);

						JarEntry newEntry = new JarEntry(resolvedName);
						out.putNextEntry(newEntry);
						out.write(bytes);
						out.closeEntry();
						writtenEntries.add(resolvedName);

						if (verbose) {
							if (!originalName.equals(resolvedName))
								System.out.println("  [shaded] " + originalName + " -> " + resolvedName);
							else
								System.out.println("  " + resolvedName);
						}
					}
				}
			}

			// Write resolved resources
			for (Map.Entry<String, byte[]> e : resolvedResources.entrySet()) {
				String name = e.getKey();
				if (writtenEntries.contains(name))
					continue;

				JarEntry newEntry = new JarEntry(name);
				out.putNextEntry(newEntry);
				out.write(e.getValue());
				out.closeEntry();
				writtenEntries.add(name);
				if (verbose) System.out.println("  " + name);
			}
		}

		System.out.println("Done: " + outputJar + (mainClass != null ? "  [Main-Class: " + mainClass + "]" : ""));
	}

	// -------------------------------------------------------------------------
	// Write a fresh MANIFEST.MF — only Main-Class if provided
	// -------------------------------------------------------------------------
	private void writeManifest(JarOutputStream out, String mainClass) throws IOException {
		StringBuilder sb = new StringBuilder();
		sb.append("Manifest-Version: 1.0\n");
		sb.append("Class-Path: .\n");
		if (mainClass != null && !mainClass.isBlank()) {
			sb.append("Main-Class: ").append(mainClass).append("\n");
		}
		sb.append("\n");

		out.putNextEntry(new JarEntry("META-INF/"));
		out.closeEntry();
		out.putNextEntry(new JarEntry(MANIFEST_PATH));
		out.write(sb.toString().getBytes());
		out.closeEntry();
	}

	// -------------------------------------------------------------------------
	// _shade suffix helper
	// -------------------------------------------------------------------------
	private String addShadeSuffix(String name) {
		int dot = name.lastIndexOf('.');
		int slash = name.lastIndexOf('/');
		if (dot > slash) {
			return name.substring(0, dot) + "_shade" + name.substring(dot);
		}
		return name + "_shade";
	}

	// -------------------------------------------------------------------------
	// ASM: rewrite bytecode references
	// -------------------------------------------------------------------------
	private byte[] relocateClass(byte[] originalBytes) {
		ClassReader reader = new ClassReader(originalBytes);
		ClassWriter writer = new ClassWriter(0);
		reader.accept(new RelocationClassVisitor(Opcodes.ASM9, writer), 0);
		return writer.toByteArray();
	}

	private class RelocationClassVisitor extends ClassVisitor {
		RelocationClassVisitor(int api, ClassVisitor cv) {
			super(api, cv);
		}

		@Override
		public void visit(int version, int access, String name, String signature,
				String superName, String[] interfaces) {
			super.visit(version, access, relocateName(name), relocateSignature(signature),
					relocateName(superName), relocateNames(interfaces));
		}

		@Override
		public FieldVisitor visitField(int access, String name, String descriptor,
				String signature, Object value) {
			return super.visitField(access, name, relocateDescriptor(descriptor),
					relocateSignature(signature), value);
		}

		@Override
		public MethodVisitor visitMethod(int access, String name, String descriptor,
				String signature, String[] exceptions) {
			MethodVisitor mv = super.visitMethod(access, name, relocateDescriptor(descriptor),
					relocateSignature(signature), relocateNames(exceptions));
			return new RelocationMethodVisitor(api, mv);
		}

		@Override
		public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
			return super.visitAnnotation(relocateDescriptor(descriptor), visible);
		}
	}

	private class RelocationMethodVisitor extends MethodVisitor {
		RelocationMethodVisitor(int api, MethodVisitor mv) {
			super(api, mv);
		}

		@Override
		public void visitTypeInsn(int opcode, String type) {
			super.visitTypeInsn(opcode, relocateName(type));
		}

		@Override
		public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
			super.visitFieldInsn(opcode, relocateName(owner), name, relocateDescriptor(descriptor));
		}

		@Override
		public void visitMethodInsn(int opcode, String owner, String name,
				String descriptor, boolean isInterface) {
			super.visitMethodInsn(opcode, relocateName(owner), name,
					relocateDescriptor(descriptor), isInterface);
		}

		@Override
		public void visitLdcInsn(Object value) {
			if (value instanceof String str) {
				String dotForm = str.replace('.', '/');
				String relocated = relocateName(dotForm);
				if (!relocated.equals(dotForm)) {
					super.visitLdcInsn(relocated.replace('/', '.'));
					return;
				}
			}
			super.visitLdcInsn(value);
		}

		@Override
		public void visitLocalVariable(String name, String descriptor, String signature,
				Label start, Label end, int index) {
			super.visitLocalVariable(name, relocateDescriptor(descriptor),
					relocateSignature(signature), start, end, index);
		}
	}

	// -------------------------------------------------------------------------
	// Name / descriptor helpers
	// -------------------------------------------------------------------------
	private String relocateName(String name) {
		if (name == null)
			return null;
		String key = name.endsWith(".class") ? name : name + ".class";
		String resolved = relocations.get(key);
		if (resolved != null) {
			return resolved.endsWith(".class")
					? resolved.substring(0, resolved.length() - 6)
					: resolved;
		}
		return name;
	}

	private String[] relocateNames(String[] names) {
		if (names == null)
			return null;
		String[] result = new String[names.length];
		for (int i = 0; i < names.length; i++)
			result[i] = relocateName(names[i]);
		return result;
	}

	private String relocateDescriptor(String descriptor) {
		if (descriptor == null)
			return null;
		StringBuilder sb = new StringBuilder();
		int i = 0;
		while (i < descriptor.length()) {
			char c = descriptor.charAt(i);
			if (c == 'L') {
				int end = descriptor.indexOf(';', i);
				if (end == -1) {
					sb.append(c);
					i++;
					continue;
				}
				String className = descriptor.substring(i + 1, end);
				sb.append('L').append(relocateName(className)).append(';');
				i = end + 1;
			} else {
				sb.append(c);
				i++;
			}
		}
		return sb.toString();
	}

	private String relocateSignature(String signature) {
		if (signature == null)
			return null;
		return relocateDescriptor(signature);
	}

	// -------------------------------------------------------------------------
	// Candidate: one copy of a resource file found in a JAR
	// -------------------------------------------------------------------------
	private static class Candidate {
		final Path jarPath;
		final byte[] bytes;

		Candidate(Path jarPath, byte[] bytes) {
			this.jarPath = jarPath;
			this.bytes = bytes;
		}
	}

	// -------------------------------------------------------------------------
	// KeepRule: --keep a.jar:com.example.lib  (prefix stored in slash form)
	// -------------------------------------------------------------------------
	private record KeepRule(String jarName, String prefix) {
		/** Returns true if jarPath's filename matches and entryName starts with prefix. */
		boolean matches(Path jarPath, String entryName) {
			return jarPath.getFileName().toString().equals(jarName)
					&& entryName.startsWith(prefix);
		}
	}

	// -------------------------------------------------------------------------
	// main()
	// -------------------------------------------------------------------------
	public static void main(String[] args) throws IOException {
		if (args.length < 2) {
			printUsage();
			System.exit(1);
		}

		String mainClass = null;
		boolean bypass = false;
		boolean verbose = false;
		List<KeepRule> keepRules = new ArrayList<>();
		Path outputJar = null;
		List<Path> inputs = new ArrayList<>();

		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--main" -> {
					if (i + 1 >= args.length) {
						System.err.println("--main requires a value");
						System.exit(1);
					}
					mainClass = args[++i];
				}
				case "-bypass" -> bypass = true;
				case "-v" -> verbose = true;
				case "--keep" -> {
					if (i + 1 >= args.length) {
						System.err.println("--keep requires a value (e.g. --keep a.jar:com.example.Class)");
						System.exit(1);
					}
					String val = args[++i];
					int colon = val.indexOf(':');
					String jar;
					String prefix;
					if (colon <= 0) {
						jar = val;
						prefix = ""; // match all entries from this JAR
					}else{
						jar    = val.substring(0, colon);
						prefix = val.substring(colon + 1).replace('.', '/');
					}
					keepRules.add(new KeepRule(jar, prefix));
				}
				default -> {
					if (outputJar == null)
						outputJar = Path.of(args[i]);
					else
						inputs.addAll(expandGlob(args[i]));
				}
			}
		}

		if (outputJar == null || inputs.isEmpty()) {
			printUsage();
			System.exit(1);
		}

		new JpmShade().merge(inputs, outputJar, mainClass, bypass, verbose, keepRules);
	}

	private static List<Path> expandGlob(String pattern) throws IOException {
		if (!pattern.contains("*") && !pattern.contains("?")) {
			return List.of(Path.of(pattern));
		}
		String normalized = pattern.replace('\\', '/');
		boolean recursive = normalized.contains("**");

		// Split into base-dir and glob portion at the first path segment that
		// contains a wildcard character.
		String[] segments = normalized.split("/");
		StringBuilder dirBuf = new StringBuilder();
		int globStart = 0;
		for (int s = 0; s < segments.length; s++) {
			if (segments[s].contains("*") || segments[s].contains("?")) {
				globStart = s;
				break;
			}
			if (dirBuf.length() > 0) dirBuf.append('/');
			dirBuf.append(segments[s]);
			globStart = s + 1;
		}
		String dirStr = dirBuf.toString();
		Path dir = dirStr.isEmpty() ? Path.of(".") : Path.of(dirStr);

		// Rebuild the glob portion (everything from first wildcard segment onward)
		String glob = String.join("/", java.util.Arrays.copyOfRange(segments, globStart, segments.length));

		if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
			if (verbose) System.err.println("[WARN] No directory found for pattern: " + pattern);
			return List.of();
		}

		// For recursive patterns use Files.walk; for flat ones use Files.list.
		// Match against the path relative to dir so ** works across segments.
		PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
		List<Path> results = new ArrayList<>();
		if (recursive) {
			// Files.walk with no FileVisitOption does NOT follow symlinks
			try (var stream = Files.walk(dir)) {
				stream.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
				      .filter(p -> matcher.matches(dir.relativize(p)))
				      .sorted()
				      .forEach(results::add);
			}
		} else {
			try (var stream = Files.list(dir)) {
				stream.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
				      .filter(p -> matcher.matches(p.getFileName()))
				      .sorted()
				      .forEach(results::add);
			}
		}
		if (results.isEmpty()) {
			if (verbose) System.err.println("[WARN] No files matched pattern: " + pattern);
		}
		return results;
	}

	private static void printUsage() {
		System.out.println(
				"Usage: jpm-shade [--main <Main-Class>] [-bypass] [-v] [--keep <jar>[:<prefix>] ...] <output.jar> <input1.jar|dir/*|dir/**> [input2.jar ...]");
		System.out.println("tip: run without -bypass first to detect collisions, then fix with --keep or by renaming files.");
		System.out.println();
		System.out.println("  --main <class>       Set Main-Class in MANIFEST.MF");
		System.out.println("  -bypass              On resource collision, keep the largest file instead of erroring");
		System.out.println("  -v                   Verbose: print every class/resource being written");
		System.out.println("  --keep <jar>[:<pfx>] On collision, always use the version from <jar> for entries");
		System.out.println("                       whose name starts with <pfx> (dot or slash form).");
		System.out.println("                       omitting [:<pfx>] matches all entries from that JAR.");
		System.out.println("                       Overrides -bypass and largest-wins. Repeatable.");
		System.out.println("  Wildcards            dir/*   - all files in directory");
		System.out.println("                       dir/**  - all files in directory and subdirectories\n");
		System.out.println("  Examples:            jpm-shade --keep inputA.jar output.jar inputA.jar lib/**");
	}
}