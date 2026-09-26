import unittest

from local_dropin_server import TailnetRegistry, is_tailscale_address


class TailnetRegistryTest(unittest.TestCase):
    def test_rejects_invalid_registration(self) -> None:
        registry = TailnetRegistry()

        self.assertFalse(registry.register("", "Phone", "100.64.1.2", 8989))
        self.assertFalse(registry.register("dropin-phone", "Phone", "", 8989))
        self.assertFalse(registry.register("dropin-phone", "Phone", "100.64.1.2", 65_536))
        self.assertEqual([], registry.peers())

    def test_returns_device_class_and_honors_exclusion(self) -> None:
        registry = TailnetRegistry()
        self.assertTrue(
            registry.register(
                "dropin-tv",
                "Living room",
                "100.64.1.2",
                8989,
                device_class="limited",
            )
        )

        self.assertEqual("limited", registry.peers()[0]["device_class"])
        self.assertEqual([], registry.peers(exclude="dropin-tv"))


class TailscaleAddressTest(unittest.TestCase):
    def test_accepts_only_valid_cgnat_addresses(self) -> None:
        self.assertTrue(is_tailscale_address("100.64.0.1"))
        self.assertTrue(is_tailscale_address("100.127.255.254"))
        self.assertFalse(is_tailscale_address("100.128.0.1"))
        self.assertFalse(is_tailscale_address("100.64.999.1"))
        self.assertFalse(is_tailscale_address("not-an-address"))


if __name__ == "__main__":
    unittest.main()
