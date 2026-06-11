# privileged — grant the qodana user passwordless sudo (clang-tidy provisioning + some scans).
# Layers onto the clang-toolchain stage.
FROM clang-toolchain AS privileged

RUN <<-EOT
	set -eux
	export DEBIAN_FRONTEND=noninteractive
	apt-get update
	apt-get install -y --no-install-recommends sudo
	echo "qodana ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/qodana
	chmod 0440 /etc/sudoers.d/qodana
	rm -rf /var/lib/apt/lists/*
EOT
