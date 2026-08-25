// Minimal vswhere.exe facade for the local llvm-mingw toolchain.
// GraalVM looks only below %ProgramFiles(x86)% for this executable.
const root = process.env.MCWEB_MSVC_ROOT;
if (!root) { console.error("vswhere-shim: MCWEB_MSVC_ROOT is not set"); process.exit(1); }
const version = "17.14.0.0";
const argv = process.argv.slice(1);
const instance = {
  instanceId: "mcweb001",
  installationName: `VisualStudio/${version}`,
  installationPath: root,
  installationVersion: version,
  productId: "Microsoft.VisualStudio.Product.BuildTools",
  isComplete: true,
  isLaunchable: true,
  isPrerelease: false,
  catalog: { productDisplayVersion: version, productLineVersion: "2022" },
};
const formatIndex = argv.indexOf("-format");
const format = formatIndex >= 0 ? String(argv[formatIndex + 1] || "").toLowerCase() : "text";
const propertyIndex = argv.indexOf("-property");
const property = propertyIndex >= 0 ? argv[propertyIndex + 1] : null;
if (format === "json") {
  process.stdout.write(JSON.stringify(property ? [{ [property]: instance[property] }] : [instance], null, 2) + "\n");
} else if (property) {
  process.stdout.write(String(instance[property] ?? "") + "\n");
} else {
  for (const [key, value] of Object.entries(instance)) {
    if (typeof value !== "object") process.stdout.write(`${key}: ${value}\n`);
  }
}
