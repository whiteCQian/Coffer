import datetime as dt
import unittest
import xml.etree.ElementTree as ET
from external_boundary import validate

class ExternalBoundaryTest(unittest.TestCase):
    now=dt.datetime(2026,10,10,tzinfo=dt.timezone.utc)
    def report(self,port=443,services=65535,age=60):
        root=ET.Element("nmaprun",scanner="nmap",start=str(int(self.now.timestamp())-age))
        ET.SubElement(root,"scaninfo",protocol="tcp",numservices=str(services))
        host=ET.SubElement(root,"host");ET.SubElement(host,"status",state="up");ET.SubElement(host,"address",addr="203.0.113.10")
        ports=ET.SubElement(host,"ports");entry=ET.SubElement(ports,"port",protocol="tcp",portid=str(port));ET.SubElement(entry,"state",state="open")
        stats=ET.SubElement(root,"runstats");ET.SubElement(stats,"finished",exit="success")
        return ET.tostring(root)
    def test_full_fresh_https_only_report_is_accepted(self):
        self.assertEqual(validate(self.report(),"203.0.113.10",self.now)["publicPorts"],[443])
    def test_other_port_partial_scan_stale_report_and_wrong_target_are_rejected(self):
        for report,ip in ((self.report(port=22),"203.0.113.10"),(self.report(services=1000),"203.0.113.10"),(self.report(age=10800),"203.0.113.10"),(self.report(),"203.0.113.11")):
            with self.subTest(ip=ip,report=report),self.assertRaises(SystemExit): validate(report,ip,self.now)

if __name__=="__main__": unittest.main()
