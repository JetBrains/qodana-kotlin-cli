# privileged — grant the qodana user passwordless sudo (clang-tidy provisioning + some scans).
# Layers onto the clang-toolchain stage.
FROM clang-toolchain AS privileged

RUN <<-EOT
	set -eux
	export DEBIAN_FRONTEND=noninteractive
	# The dhi.io hardened base ships /etc/passwd with no root (uid 0) entry, so sudo's postinst
	# `chown root:root` fails to resolve the name. Add a minimal root entry before installing sudo.
	grep -q '^root:' /etc/passwd || echo 'root:x:0:0:root:/root:/bin/sh' >> /etc/passwd
	apt-get update
	apt-get install -y --no-install-recommends sudo
	echo "qodana ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/qodana
	chmod 0440 /etc/sudoers.d/qodana
	rm -rf /var/lib/apt/lists/*
EOT
