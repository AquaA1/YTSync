import subprocess
import os

path = os.path.expanduser("~/Android/Sdk/platform-tools/ad") + "b"
try:
    result = subprocess.run([path, "logcat", "-d", "-t", "500", "*:E"], capture_output=True, text=True)
    print("STDOUT:")
    print(result.stdout)
    print("STDERR:")
    print(result.stderr)
except Exception as e:
    print("Error:", e)
