"""fixtures: require a device, install the apk, enable the service, then reveal a
legacy token and turn on all three surfaces through the ui."""

import adb
import pytest


@pytest.fixture(scope="session", autouse=True)
def require_device():
    if not adb.device_available():
        pytest.skip("no adb device attached; skipping e2e", allow_module_level=False)
    # a sleeping screen is just woken; a keyguard is the user's to dismiss. without
    # this the whole suite fails in setup with "tab not found", which says nothing
    # about the real cause.
    adb.wake()
    if adb.locked():
        pytest.skip("device is locked; unlock it to run the e2e suite")
    # claim the host port before touching the device, so a collision with another
    # adb server is named up front rather than after an install onto a device the
    # http tests will not actually be talking to.
    adb.forward()


@pytest.fixture(scope="session")
def installed(require_device):
    if not adb.APK.exists():
        pytest.skip(f"debug apk not built ({adb.APK}); run `make` first")
    adb.install()
    adb.enable_accessibility()
    adb.launch()
    return True


@pytest.fixture(scope="session")
def token(installed):
    """reveal a legacy token and enable intents + http + mcp, then forward the port."""
    tok = adb.reveal_token()
    adb.select_tab("surfaces")
    for label in ("intents", "local http", "mcp server"):
        adb.ensure_switch(label, True)
    adb.forward()
    return tok


@pytest.fixture
def approval_on(token):
    """turn on global approval enforcement for one test, restoring off after."""
    adb.set_approval(True)
    yield
    adb.set_approval(False)
