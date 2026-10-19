功能	命令
禁止 IPv4 + IPv6 Ping	sudo bash ping-control.sh disable
允许 IPv4 + IPv6 Ping	sudo bash ping-control.sh enable
永久禁止 Ping	sudo bash ping-control.sh disable --persist
永久允许 Ping	sudo bash ping-control.sh enable --persist
查看当前状态	./ping-control.sh status