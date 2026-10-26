#!/usr/bin/env python3
"""Build and verify a release on Linux x86_64 using the latest stable PyCharm."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shlex
import subprocess
import tarfile
import urllib.parse
import urllib.request


def download(url, target, checksum_url=None):
    partial = target.with_suffix(target.suffix + ".part")
    print(f"Downloading {target.name}", flush=True)
    urllib.request.urlretrieve(url, partial)
    if checksum_url:
        with urllib.request.urlopen(checksum_url, timeout=30) as response:
            expected = response.read().decode().split()[0]
        with partial.open("rb") as stream:
            actual = hashlib.file_digest(stream, "sha256").hexdigest()
        if actual != expected:
            partial.unlink()
            raise RuntimeError(f"Checksum mismatch: {target.name}")
    partial.replace(target)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache-dir", type=Path, default=Path(
        os.environ.get("XDG_CACHE_HOME", str(Path.home() / ".cache"))) / "opencode-ui-build")
    parser.add_argument("--skip-verifier", action="store_true",
                        help="Run tests and packaging without Plugin Verifier")
    parser.add_argument("--verify-only", action="store_true", help="Resume the compatibility check after a build")
    parser.add_argument("--verbose", action="store_true", help="Include Gradle dependency diagnostics")
    args = parser.parse_args()
    if args.skip_verifier and args.verify_only:
        parser.error("--skip-verifier and --verify-only cannot be combined")
    if platform.system() != "Linux" or platform.machine() not in ("x86_64", "amd64"):
        parser.error("This bootstrap supports Linux x86_64; use the Gradle wrapper on other platforms.")
    cache = args.cache_dir.resolve()
    cache.mkdir(parents=True, exist_ok=True)
    project = Path(__file__).resolve().parents[1]

    # Only the official release channel is eligible; latest can otherwise include EAP.
    url = "https://data.services.jetbrains.com/products/releases?code=PCP&latest=true&type=release"
    with urllib.request.urlopen(url, timeout=30) as response:
        release = json.load(response)["PCP"][0]
    version = release["version"]
    print(f"Target: PyCharm {version}, build {release['build']}", flush=True)
    sdk = cache / f"pycharm-{version}"
    if not (sdk / "product-info.json").is_file():
        info = release["downloads"]["linux"]
        archive = cache / f"pycharm-{version}.tar.gz"
        download(info["link"], archive, info.get("checksumLink", info["link"] + ".sha256"))
        with tarfile.open(archive) as bundle:
            bundle.extractall(cache, filter="data")
    metadata = json.loads((sdk / "product-info.json").read_text())
    if metadata["version"] != version or metadata["buildNumber"] != release["build"]:
        raise RuntimeError("Cached SDK does not match the selected stable release")

    # Gradle runs on JDK 21; the IntelliJ plugin selects the SDK's bundled JBR for tests.
    jdks = list(cache.glob("zulu21*-ca-jdk21*-linux_x64"))
    if not jdks:
        archive = cache / "jdk21.tar.gz"
        download("https://cdn.azul.com/zulu/bin/zulu21.40.17-ca-jdk21.0.6-linux_x64.tar.gz", archive)
        with tarfile.open(archive) as bundle:
            bundle.extractall(cache, filter="data")
        jdks = list(cache.glob("zulu21*-ca-jdk21*-linux_x64"))
    jdk = jdks[0]
    if not (jdk / "bin/javac").is_file():
        raise RuntimeError("A full JDK with javac is required")
    env = os.environ.copy()
    env["JAVA_HOME"] = str(jdk)
    env["PATH"] = str(jdk / "bin") + os.pathsep + env["PATH"]
    proxy_options = []
    for scheme in ("http", "https"):
        proxy = env.get(scheme.upper() + "_PROXY") or env.get(scheme + "_proxy")
        if proxy:
            value = urllib.parse.urlparse(proxy)
            if value.username or value.password:
                raise RuntimeError("Authenticated proxies require an explicit local Gradle configuration")
            proxy_options += [f"-D{scheme}.proxyHost={value.hostname}",
                              f"-D{scheme}.proxyPort={value.port or 80}"]
    trust = Path("/etc/ssl/certs/java/cacerts")
    if trust.is_file():
        proxy_options.append(f"-Djavax.net.ssl.trustStore={trust}")
    # Wrapper bootstrap and the Gradle build both need these properties.
    env["GRADLE_OPTS"] = env.get("GRADLE_OPTS", "") + " " + shlex.join(proxy_options)
    env["JAVA_TOOL_OPTIONS"] = env.get("JAVA_TOOL_OPTIONS", "") + " " + shlex.join(proxy_options)
    tasks = ["test", "buildPlugin", "verifyPluginStructure"]
    command = ["bash", "./gradlew", "--no-daemon", "--max-workers=2",
               "-Dorg.gradle.jvmargs=-Xmx2048m",
               f"-Porg.gradle.java.installations.paths={jdk},{sdk / 'jbr'}",
               f"-PtargetJavaVersion={metadata['minRequiredJavaVersion']}",
               f"-PlocalIdePath={sdk}", f"-PpycharmVersion={version}", *proxy_options, *tasks]
    if args.verbose:
        command.append("--info")
    if not args.verify_only:
        subprocess.run(command, cwd=project, env=env, check=True)
    if not args.skip_verifier:
        verifier_command = [item for item in command if item not in tasks]
        subprocess.run([*verifier_command, "verifyPlugin"], cwd=project, env=env, check=True)
    print(f"Release ZIP: {project / 'build/distributions'}", flush=True)


if __name__ == "__main__":
    main()
