#!/bin/bash
set -euo pipefail
if [ $# -ne 3 ]; then echo "usage: $0 BASE.img.xz SOURCE_DIR OUT.img.xz" >&2; exit 2; fi
BASE=$1
SRC=$2
OUT=$3
WORK=$(mktemp -d)
ROOT="$WORK/root"
BOOT="$WORK/boot"
LOOP=''
RESOLV_KIND='none'
RESOLV_LINK=''

cleanup(){
  set +e
  for p in "$ROOT/dev/pts" "$ROOT/dev" "$ROOT/proc" "$ROOT/sys" "$BOOT" "$ROOT"; do
    mountpoint -q "$p" && sudo umount -l "$p"
  done
  [ -n "$LOOP" ] && sudo losetup -d "$LOOP" 2>/dev/null || true
  rm -rf "$WORK"
}
trap cleanup EXIT

command -v qemu-aarch64-static >/dev/null || { echo 'qemu-aarch64-static is required on build host' >&2; exit 4; }
command -v growpart >/dev/null || { echo 'growpart is required on build host' >&2; exit 4; }

xz -t "$BASE"
xz -dc "$BASE" > "$WORK/base.img"
# The official Lite image intentionally leaves little free space. Grow the
# image/rootfs before installing Chromium/X11 so package installation is done
# once at build time rather than on the Raspberry Pi.
truncate -s +2G "$WORK/base.img"
LOOP=$(sudo losetup --find --show --partscan "$WORK/base.img")
sudo growpart "$LOOP" 2
sudo partprobe "$LOOP" || true
sudo losetup -c "$LOOP"
ROOTPART="${LOOP}p2"
BOOTPART="${LOOP}p1"
[ -b "$ROOTPART" ] || { echo 'rootfs partition p2 not found' >&2; exit 3; }
[ -b "$BOOTPART" ] || { echo 'boot partition p1 not found' >&2; exit 3; }
sudo e2fsck -f -y "$ROOTPART"
sudo resize2fs "$ROOTPART"
mkdir -p "$ROOT" "$BOOT"
sudo mount "$ROOTPART" "$ROOT"
sudo mount "$BOOTPART" "$BOOT"

sudo mkdir -p "$ROOT/opt/yay/app/yay" "$ROOT/opt/yay/web" "$ROOT/opt/yay/scripts" "$ROOT/var/lib/yay/assets" "$ROOT/var/lib/yay/publications" "$ROOT/var/lib/yay/screenshots"
sudo cp -a "$SRC/yay/." "$ROOT/opt/yay/app/yay/"
sudo cp -a "$SRC/web/." "$ROOT/opt/yay/web/"
sudo cp -a "$SRC/scripts/." "$ROOT/opt/yay/scripts/"
sudo cp -a "$SRC/systemd/." "$ROOT/etc/systemd/system/"
sudo install -m 0600 "$SRC/config/device.json" "$ROOT/var/lib/yay/device.json"
sudo chmod +x "$ROOT/opt/yay/scripts/"*

# Run ARM64 package installation inside the image at build time so first boot
# never depends on live mirrors or a user-triggered apt update.
sudo install -m 0755 "$(command -v qemu-aarch64-static)" "$ROOT/usr/bin/qemu-aarch64-static"
if [ -L "$ROOT/etc/resolv.conf" ]; then
  RESOLV_KIND='link'
  RESOLV_LINK=$(readlink "$ROOT/etc/resolv.conf")
elif [ -f "$ROOT/etc/resolv.conf" ]; then
  RESOLV_KIND='file'
  sudo cp -a "$ROOT/etc/resolv.conf" "$WORK/resolv.conf.original"
fi
sudo rm -f "$ROOT/etc/resolv.conf"
sudo cp /etc/resolv.conf "$ROOT/etc/resolv.conf"
echo '#!/bin/sh' | sudo tee "$ROOT/usr/sbin/policy-rc.d" >/dev/null
echo 'exit 101' | sudo tee -a "$ROOT/usr/sbin/policy-rc.d" >/dev/null
sudo chmod 0755 "$ROOT/usr/sbin/policy-rc.d"
sudo mount --bind /dev "$ROOT/dev"
sudo mount --bind /dev/pts "$ROOT/dev/pts"
sudo mount -t proc proc "$ROOT/proc"
sudo mount -t sysfs sys "$ROOT/sys"

sudo chroot "$ROOT" /usr/bin/qemu-aarch64-static /bin/bash -lc '
  set -euo pipefail
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -o Acquire::Retries=5 -o Acquire::PDiffs=false
  apt-get install -y --no-install-recommends xserver-xorg xinit openbox chromium python3-requests python3-cryptography scrot watchdog fonts-dejavu-core ca-certificates
  id yay >/dev/null 2>&1 || useradd -r -m -s /usr/sbin/nologin yay
  usermod -a -G video,audio,input,render yay || true
  chown -R yay:yay /var/lib/yay /home/yay
  apt-get clean
  rm -rf /var/lib/apt/lists/* /var/cache/apt/archives/*.deb
'

sudo rm -f "$ROOT/usr/sbin/policy-rc.d" "$ROOT/usr/bin/qemu-aarch64-static" "$ROOT/etc/resolv.conf"
if [ "$RESOLV_KIND" = 'link' ]; then
  sudo ln -s "$RESOLV_LINK" "$ROOT/etc/resolv.conf"
elif [ "$RESOLV_KIND" = 'file' ]; then
  sudo cp -a "$WORK/resolv.conf.original" "$ROOT/etc/resolv.conf"
fi
for p in "$ROOT/dev/pts" "$ROOT/dev" "$ROOT/proc" "$ROOT/sys"; do
  mountpoint -q "$p" && sudo umount "$p"
done

sudo install -m 0755 "$SRC/scripts/yay-control" "$ROOT/usr/local/sbin/yay-control"
sudo install -m 0755 "$SRC/scripts/yay-kiosk-session" "$ROOT/usr/local/bin/yay-kiosk-session"
sudo install -m 0755 "$SRC/scripts/yay-detect-hardware" "$ROOT/usr/local/bin/yay-detect-hardware"
sudo tee "$ROOT/etc/sudoers.d/yay-control" >/dev/null <<'EOF'
yay ALL=(root) NOPASSWD: /usr/local/sbin/yay-control *
EOF
sudo chmod 0440 "$ROOT/etc/sudoers.d/yay-control"

# The appliance owns tty1. Prevent the console login service from racing Xorg.
sudo ln -sf /dev/null "$ROOT/etc/systemd/system/getty@tty1.service"
sudo rm -f "$ROOT/etc/systemd/system/multi-user.target.wants/yay-firstboot.service"
sudo systemctl --root="$ROOT" enable yay-local-player.service yay-agent.service yay-kiosk.service >/dev/null
sudo systemctl --root="$ROOT" enable watchdog.service >/dev/null 2>&1 || true

# Raspberry Pi OS otherwise asks for a username/password on first boot. The
# documented userconf mechanism bypasses that wizard. This maintenance account
# gets a build-random password and is not used by the kiosk runtime.
RANDOM_PASSWORD=$(openssl rand -hex 32)
PASSWORD_HASH=$(printf '%s' "$RANDOM_PASSWORD" | openssl passwd -6 -stdin)
printf 'yayadmin:%s\n' "$PASSWORD_HASH" | sudo tee "$BOOT/userconf.txt" >/dev/null
unset RANDOM_PASSWORD PASSWORD_HASH

if [ -f "$BOOT/config.txt" ]; then
  grep -q '^dtparam=watchdog=on' "$BOOT/config.txt" || echo 'dtparam=watchdog=on' | sudo tee -a "$BOOT/config.txt" >/dev/null
fi

sync
sudo umount "$BOOT"
sudo umount "$ROOT"
sudo losetup -d "$LOOP"
LOOP=''

xz -T0 -6 -f -c "$WORK/base.img" > "$OUT"
xz -t "$OUT"
