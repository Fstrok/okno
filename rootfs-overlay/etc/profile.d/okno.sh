# Nothing runs login(1) under proot, so show the greeting from the login shell.
[ -r /etc/motd ] && cat /etc/motd
