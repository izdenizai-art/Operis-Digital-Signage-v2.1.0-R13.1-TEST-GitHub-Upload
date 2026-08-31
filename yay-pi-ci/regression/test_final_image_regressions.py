from pathlib import Path

R = Path(__file__).resolve().parents[1]


def test_setup_form_is_stable_and_uses_separate_manual_fields():
    text = (R / "web" / "player.js").read_text(encoding="utf-8")
    assert 'id="host"' in text
    assert 'id="port"' in text
    assert 'id="protocol"' in text
    assert "8080" not in text
    assert "setupVisible" in text
    assert "if(!setupVisible)" in text.replace(" ", "")


def test_kiosk_owns_tty1_without_getty_conflict():
    unit = (R / "systemd" / "yay-kiosk.service").read_text(encoding="utf-8")
    assert "Conflicts=getty@tty1.service" in unit


def test_image_provisions_gui_before_first_boot_and_bypasses_user_wizard():
    inject = (R / "image" / "inject-image.sh").read_text(encoding="utf-8")
    assert "qemu-aarch64-static" in inject
    assert "apt-get install" in inject
    assert "userconf.txt" in inject
    assert "getty@tty1.service" in inject
    assert "multi-user.target.wants/yay-firstboot.service" not in inject


def test_firstboot_no_longer_downloads_runtime_packages():
    firstboot = (R / "scripts" / "yay-firstboot").read_text(encoding="utf-8")
    assert "apt-get install" not in firstboot
    assert "apt-get update" not in firstboot
