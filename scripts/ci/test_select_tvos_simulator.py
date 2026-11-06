import unittest
from select_tvos_simulator import select_runtime


def runtime(version, available=True, devices=None):
    return {"identifier": "com.apple.CoreSimulator.SimRuntime.tvOS-" + version.replace(".", "-"),
            "version": version, "isAvailable": available,
            "supportedDeviceTypes": devices if devices is not None else [
                {"name": "Apple TV 4K (3rd generation)", "identifier": "tv-4k"}]}


class RuntimeSelectionTests(unittest.TestCase):
    def test_newest_available_supported_runtime(self):
        payload = {"runtimes": [runtime("18.9"), runtime("18.10"), runtime("26.0", False)]}
        self.assertEqual(select_runtime(payload), ("com.apple.CoreSimulator.SimRuntime.tvOS-18-10", "tv-4k"))

    def test_does_not_select_unsupported_device(self):
        self.assertEqual(select_runtime({"runtimes": [runtime("18.0", devices=[
            {"name": "Apple TV", "identifier": "old-tv"}]) ]})[1], "old-tv")

    def test_rejects_missing_unavailable_old_or_unsupported_runtimes(self):
        for runtimes in ([], [runtime("26.0", False)], [runtime("17.0")], [runtime("18.0", devices=[])]):
            with self.subTest(runtimes=runtimes), self.assertRaises(ValueError):
                select_runtime({"runtimes": runtimes})

    def test_ignores_non_tv_runtime(self):
        other = runtime("99.0")
        other["identifier"] = "com.apple.CoreSimulator.SimRuntime.iOS-99-0"
        self.assertEqual(select_runtime({"runtimes": [other, runtime("18.0")]})[1], "tv-4k")


if __name__ == "__main__":
    unittest.main()
