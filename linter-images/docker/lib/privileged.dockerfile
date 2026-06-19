# privileged — grant the qodana user passwordless sudo (clang-tidy provisioning + some scans).
# Layers onto ${PRIVILEGED_BASE_STAGE} — the consuming image's final pre-dist stage (base.dockerfile
# defaults it to clang-toolchain; each image overrides as needed).
FROM ${PRIVILEGED_BASE_STAGE} AS privileged

RUN <<-EOT
	set -eux
	export DEBIAN_FRONTEND=noninteractive
	# The dhi.io hardened base ships /etc/passwd AND /etc/group with no root (uid/gid 0) entry, so
	# sudo's postinst `chown root:root` can't resolve the name. Add minimal root entries before install.
	grep -q '^root:' /etc/passwd || echo 'root:x:0:0:root:/root:/bin/sh' >> /etc/passwd
	grep -q '^root:' /etc/group || echo 'root:x:0:' >> /etc/group
	apt-get update
	apt-get install -y --no-install-recommends sudo
	echo "qodana ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/qodana
	chmod 0440 /etc/sudoers.d/qodana
	rm -rf /var/lib/apt/lists/*
EOT
