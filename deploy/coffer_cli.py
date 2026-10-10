import subprocess
import sys
from preflight import compose
raise SystemExit(subprocess.call(compose(*sys.argv[1:])))
