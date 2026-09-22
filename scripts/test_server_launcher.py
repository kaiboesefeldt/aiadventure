"""Isolated launcher contracts; no real models, network listeners or Gradle runs."""
import json
import os
from pathlib import Path
import shlex
import shutil
import signal
import subprocess
import tempfile
import time
import unittest


ROOT = Path(__file__).resolve().parents[1]
FAKE_SERVER = '''#!/usr/bin/env python3
import json, os, pathlib, signal, sys
root = pathlib.Path(os.environ["LAUNCHER_TEST_DIR"])
args = sys.argv[1:]
port = args[args.index("--port") + 1]
(root / (port + ".pid")).write_text(str(os.getpid()))
(root / (port + ".json")).write_text(json.dumps(args))
signal.pause()
'''
FAKE_GRADLE = '''#!/usr/bin/env python3
import json, os, pathlib, signal, sys
root = pathlib.Path(os.environ["LAUNCHER_TEST_DIR"])
(root / "gradle.pid").write_text(str(os.getpid()))
(root / "gradle.json").write_text(json.dumps(sys.argv[1:]))
signal.pause()
'''


class LauncherTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="adventure-launcher-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        shutil.copy(ROOT / "server.sh", self.root / "server.sh")
        self.write_executable("llama-server", FAKE_SERVER)
        self.write_executable("gradlew", FAKE_GRADLE)
        self.model = self.root / "model with spaces.gguf"
        self.model.touch()
        self.config = dict(PRIMARY_MODEL_PATH=str(self.model), PRIMARY_THREADS="4",
                           PRIMARY_PORT="18081", LOG_LEVEL="INFO")
        self.env = {key: value for key, value in os.environ.items()
                    if not key.startswith("PRIMARY_")}
        self.env.update(PATH=str(self.root) + os.pathsep + os.environ["PATH"],
                        LAUNCHER_TEST_DIR=str(self.root))
        self.log = (self.root / "output").open("w")
        self.addCleanup(self.log.close)
        self.process = None
        self.addCleanup(self.emergency_cleanup)

    def write_executable(self, name, contents):
        path = self.root / name
        path.write_text(contents)
        path.chmod(0o755)
        return path

    def wrapper(self, role):
        path = self.write_executable(role + " wrapper.sh", '''#!/usr/bin/env bash
printf '%s' "$$" > "$LAUNCHER_TEST_DIR/$(basename "$0").pid"
"$1" --model "wrapper model.gguf" -t 1 "${@:2}"
''')
        self.config[role + "_MODEL_SCRIPT"] = str(path)
        self.config.pop(role + "_MODEL_PATH", None)
        self.config.pop(role + "_THREADS", None)
        return path

    def start(self):
        (self.root / ".env").write_text("".join(
            f"{key}={shlex.quote(value)}\n" for key, value in self.config.items()))
        self.process = subprocess.Popen(["bash", "server.sh"], cwd=self.root,
                                        env=self.env, stdout=self.log, stderr=self.log)

    def output(self):
        return (self.root / "output").read_text()

    def wait_for(self, predicate):
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            if predicate():
                return
            if self.process.poll() is not None:
                self.fail("Launcher exited unexpectedly: " + self.output())
            time.sleep(0.02)
        self.fail("Timed out: " + self.output())

    def ready(self):
        self.wait_for(lambda: all((self.root / (name + ".json")).exists()
                                 for name in ("18081", "gradle")))

    def args(self, name):
        return json.loads((self.root / (name + ".json")).read_text())

    def alive(self, pid):
        try:
            # Orphan zombies may await reaping by the container init.
            return Path(f"/proc/{pid}/stat").read_text().split(") ", 1)[1][0] != "Z"
        except FileNotFoundError:
            return False

    def emergency_cleanup(self):
        if self.process and self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait()
        for path in self.root.glob("*.pid"):
            pid = int(path.read_text())
            if self.alive(pid):
                os.kill(pid, signal.SIGKILL)

    def assert_clean(self):
        for path in self.root.glob("*.pid"):
            self.assertFalse(self.alive(int(path.read_text())), str(path))
        args_file = self.root / "18081.json"
        if args_file.exists():
            args = self.args("18081")
            self.assertFalse(Path(args[args.index("--slot-save-path") + 1]).exists())

    def stop(self):
        self.process.terminate()
        self.assertEqual(143, self.process.wait(timeout=10), self.output())
        self.assert_clean()

    def assert_launch(self, primary_wrapper=False):
        self.start()
        self.ready()
        args = self.args("18081")
        expected = (["--model", "wrapper model.gguf", "-t", "1"] if primary_wrapper
                    else ["-t", "4"]) + ["-rea", "off", "--port", "18081"]
        slots = args[args.index("--slot-save-path") + 1]
        self.assertTrue(Path(slots).is_dir())
        expected += ["--slot-save-path", slots]
        if not primary_wrapper:
            expected += ["--model", str(self.model)]
        self.assertEqual(expected, args)
        self.assertEqual(["--console=plain", ":server:run",
                          "--args=--llama-port=18081 --log-level=INFO"],
                         self.args("gradle"))
        self.stop()

    def test_legacy_launch(self):
        self.assert_launch()

    def test_wrapper_without_model_or_threads(self):
        self.wrapper("PRIMARY")
        self.assert_launch(True)

    def test_primary_wrapper_overrides_invalid_model_and_threads(self):
        self.wrapper("PRIMARY")
        self.config.update(PRIMARY_MODEL_PATH="/missing/model", PRIMARY_THREADS="invalid")
        self.assert_launch(True)

    def test_empty_scripts_preserve_legacy_mode(self):
        self.config.update(PRIMARY_MODEL_SCRIPT="")
        self.assert_launch()

    def test_relative_wrapper_path(self):
        path = self.wrapper("PRIMARY")
        self.config["PRIMARY_MODEL_SCRIPT"] = path.name
        # A bare relative filename must not depend on PATH lookup.
        bin_dir = self.root / "bin"
        bin_dir.mkdir()
        (self.root / "llama-server").rename(bin_dir / "llama-server")
        self.env["PATH"] = str(bin_dir) + os.pathsep + os.environ["PATH"]
        self.assert_launch(True)

    def assert_rejected(self, message):
        self.start()
        self.assertNotEqual(0, self.process.wait(timeout=5))
        self.assertIn(message, self.output())
        self.assertEqual([], list(self.root.glob("*.pid")))

    def test_missing_wrapper_rejected_before_start(self):
        self.config["PRIMARY_MODEL_SCRIPT"] = str(self.root / "missing.sh")
        self.assert_rejected("PRIMARY_MODEL_SCRIPT must be a readable executable regular file.")

    def test_non_executable_wrapper_rejected(self):
        self.wrapper("PRIMARY").chmod(0o644)
        self.assert_rejected("PRIMARY_MODEL_SCRIPT must be a readable executable regular file.")

    def test_directory_wrapper_rejected(self):
        self.config["PRIMARY_MODEL_SCRIPT"] = str(self.root)
        self.assert_rejected("PRIMARY_MODEL_SCRIPT must be a readable executable regular file.")

    def test_invalid_port_rejected_in_wrapper_mode(self):
        self.wrapper("PRIMARY")
        self.config["PRIMARY_PORT"] = "18446744073709551616"
        self.assert_rejected("PRIMARY_PORT must be an integer between 1 and 65535.")

    def test_invalid_legacy_threads_rejected(self):
        self.config["PRIMARY_THREADS"] = "0"
        self.assert_rejected("PRIMARY_THREADS must be a positive integer.")

    def test_wrapper_exit_cleans_remaining_children(self):
        path = self.wrapper("PRIMARY")
        path.write_text('''#!/usr/bin/env bash
"$1" "${@:2}" &
while [[ ! -f "$LAUNCHER_TEST_DIR/exit-now" ]]; do sleep 0.02; done
exit 7
''')
        self.start()
        self.ready()
        (self.root / "exit-now").touch()
        self.assertEqual(7, self.process.wait(timeout=10), self.output())
        self.assert_clean()


if __name__ == "__main__":
    unittest.main(verbosity=2)
