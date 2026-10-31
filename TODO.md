[ ] fix the logout bug(exit hang from ssh/mosh).
[ ] fix sync errors and deduplicate/merge(newest wins).
[ ] fix group renaming bugs.
[ ] fix main thread and slow actions.
[ ] fix keyboard resizing/toggle ui issues(IE: vim/nvim breaks)
[ ] there are other bugs that also must be fixed, but will require code tracing.

# do not implement these as i am unsure about the items below, just thinking but...
[ ] remove the cloud vm hosts from the the items as the change below would now end up creating duplicates.
[ ] might change my mind and move SSH ONLY cloud(not hypervisors) VMs to the hosts and only leave vm control and not connection. so maybe in hosts we can set th4 group as {name}@{provider}, as this would keep them separated and organized. 
