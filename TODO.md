[ ] add herdr multiplexer support, and others.
[ ] make Routes/Forwarder tor be always enabled if tor binary is bundled(no toggle on of as its bundled and already strating), and also fix test, maybe return status from the tor check, where is just says sucess/failed if failed report why.
[ ] can not expand collapse ungrouped hostd so they always show.
[ ] try to find and fix vim displaying content oddly, duplicate statusline, might be vim and not app though.

# do not implement these as i am unsure about the items below, just thinking!
[ ] remove the cloud vm hosts from the the items as the change below would now end up creating duplicates. now they will just 
[ ] might change my mind and move SSH ONLY cloud(not hypervisors) VMs to the hosts and only leave vm control and not connection. so maybe in hosts we can set th4 group as {name}@{provider}, as this would keep them separated and organized. 
