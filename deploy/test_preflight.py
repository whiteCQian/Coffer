import copy
import json
import sys
import unittest
from pathlib import Path
from preflight import validate_config

# Input is produced by the actual Docker Compose CLI, not a hand-maintained YAML mock.
CONFIG = json.loads(Path(sys.argv.pop(1)).read_text(encoding="utf-8-sig"))

class DeploymentBoundaryTest(unittest.TestCase):
    def test_production_boundaries(self):
        validate_config(CONFIG)
    def test_published_database_is_rejected(self):
        value = copy.deepcopy(CONFIG)
        value["services"]["mysql"]["ports"] = [{"published":"3306", "target":3306}]
        with self.assertRaises(SystemExit): validate_config(value)
    def test_root_secret_in_backend_is_rejected(self):
        value = copy.deepcopy(CONFIG)
        value["services"]["backend"]["secrets"].append({"source":"minio_root_password"})
        with self.assertRaises(SystemExit): validate_config(value)
    def test_ephemeral_mysql_is_rejected(self):
        value = copy.deepcopy(CONFIG)
        value["services"]["mysql"]["volumes"] = []
        with self.assertRaises(SystemExit): validate_config(value)
    def test_redis_gate_is_rejected(self):
        value = copy.deepcopy(CONFIG)
        value["services"]["backend"]["depends_on"]["redis"] = {"condition":"service_healthy"}
        with self.assertRaises(SystemExit): validate_config(value)
    def test_plaintext_database_is_rejected(self):
        value = copy.deepcopy(CONFIG)
        value["services"]["backend"]["environment"]["COFFER_DB_URL"] = "jdbc:mysql://mysql/coffer?sslMode=DISABLED"
        with self.assertRaises(SystemExit): validate_config(value)
    def test_plaintext_minio_is_rejected(self):
        value = copy.deepcopy(CONFIG)
        value["services"]["backend"]["environment"]["MINIO_ENDPOINT"] = "http://minio:9000"
        with self.assertRaises(SystemExit): validate_config(value)
    def test_wrong_capacity_volume_is_rejected(self):
        value = copy.deepcopy(CONFIG)
        value["services"]["capacity"]["volumes"] = []
        with self.assertRaises(SystemExit): validate_config(value)

if __name__ == "__main__": unittest.main()
