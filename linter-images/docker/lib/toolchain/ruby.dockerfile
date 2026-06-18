# toolchain/ruby — redirect the gem/bundle cache to the writable /data mount.
# In-place (no `FROM`): layers onto the current stage — `base` for ruby. Ruby is PRE-BAKED in the
# dhi.io/ruby base (ruby/gem/bundle at /usr/local/bin, already on PATH for every stage incl. the
# runtime user), so this fragment installs NOTHING. The ruby base defaults GEM_HOME to
# /usr/local/bundle, which is ROOT-owned; the image runs scans as the unprivileged qodana user (uid
# 1000), so any Ruby project that resolves project gems (`gem install` / `bundle install`) would fail
# to populate that cache. lib/base.dockerfile creates /data/cache owned by the qodana user, so point
# the gem/bundle cache there. This follows lib/toolchain/go.dockerfile's ENV-redirect idiom (NOT the
# source qodana-cli ruby.Dockerfile, which writes a HOME-relative BUNDLE_PATH into $HOME/.bundle/config
# and sets neither GEM_HOME nor BUNDLE_APP_CONFIG): redirect ALL THREE to /data/cache/gem to cover both
# `gem install` (reads GEM_HOME) and `bundle install` (reads BUNDLE_PATH/BUNDLE_APP_CONFIG).
# Gem-installed executables land at $GEM_HOME/bin (=/data/cache/gem/bin), which the base PATH does not
# carry, so prepend it so project tools (e.g. rubocop) run as uid 1000. Consumes: nothing.
ENV GEM_HOME=/data/cache/gem \
	BUNDLE_PATH=/data/cache/gem \
	BUNDLE_APP_CONFIG=/data/cache/gem
ENV PATH="/data/cache/gem/bin:${PATH}"
