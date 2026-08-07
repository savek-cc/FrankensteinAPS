# Project Preferences

This checkout is a fork. Read @docs/fork-patches/FORK-GUIDE.md for what the two branches are, how to
sync them with upstream and how to build here.

This is the `pre-ui-rewrite` branch: our patch stack on top of `origin/master`, the upstream 3.4.x
maintenance line. It keeps the old UI and runs on the child phone. The other line is `dev`, which
sits on `origin/dev` and has the new Compose UI.

Upstream has no CLAUDE.md on this branch, so this file is ours alone and never conflicts on a
rebase.
