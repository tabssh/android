[x] Proxmox console selection prefers SPICE, then VNC, then serial, and fails only when every configured console option is unavailable.
[x] Infra > Docker host lists refresh from Room and render when returning to the Containers page.
[ ] add support for other multiplexers (names not specified yet).
[ ] try to find and fix vim displaying content oddly, duplicate statusline, might be vim and not app though when keyboard toggles so it seems its a resize issue.

# do not implement these as i am unsure about the items below, just thinking!
[ ] remove the cloud vm hosts from the the items as the change below would now end up creating duplicates. now they will just 
[ ] might change my mind and move SSH ONLY cloud(not hypervisors) VMs to the hosts and only leave vm control and not connection. so maybe in hosts we can set th4 group as {name}@{provider}, as this would keep them separated and organized. 
