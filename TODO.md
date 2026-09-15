[ ] tab atate shpuld be cleared 9n close/exit/disconnect
[ ] fix panes ssh exit/reboot/disconnect not actually disconnecting bug
[ ] fix panes app not responding bug
[ ] fix tabs dying on another tabs disconnect bug(not sure where this bug is).
[ ] fix vv/jnlp download then open with tabssh not working bug. 
[ ] fix custom keyboard not working for vnc/spice/ffb/etc, IE: control, pre, shift, etc.
[ ] update README.md, whats_new.md.
[ ] VPS/Domqin Tracker UI/UX enhancements such as adding color, etc.
[ ] pasting into a vnc/spice/rfb loses characters/very stuttery.

[ ] fix all issues from this log and ensure there not any other issues:
=== TabSSH Debug Log ===
Exported: 2026-09-14 23:35:42.195
App Version: 1.0.0 (11)
Build Commit: 1fd42a2
Debug Log: true
Log File: /data/user/0/io.github.tabssh/files/tabssh_debug.log
Log Size: 23200 bytes
========================

2026-09-14 23:34:59.628 I/TabSSH:Logger: Debug log cleared by user
2026-09-14 23:35:01.630 D/TabSSH:SessionPersistenceManager: Activity paused: MainActivity
2026-09-14 23:35:01.648 D/TabSSH:SessionPersistenceManager: Activity created: ContainerHostManagerActivity
2026-09-14 23:35:01.665 I/TabSSH:ContainerSessionManager: acquiring docker session for host 11 (force=false)
2026-09-14 23:35:01.667 D/TabSSH:SessionPersistenceManager: Activity started, active count: 2
2026-09-14 23:35:01.668 D/TabSSH:SessionPersistenceManager: Activity resumed: ContainerHostManagerActivity
2026-09-14 23:35:01.669 D/TabSSH:ContainerSessionManager: opening SSH connection for docker host 11
2026-09-14 23:35:01.669 D/TabSSH:SSHSessionManager: Creating connection for server3
2026-09-14 23:35:01.670 D/TabSSH:SSHConnection: Created connection for server3
2026-09-14 23:35:01.671 I/TabSSH:SSHSessionManager: Created new connection for server3
2026-09-14 23:35:01.673 D/TabSSH:SSHConnection: Profile identityId: null
2026-09-14 23:35:01.674 D/TabSSH:SSHConnection: No identity linked to this connection
2026-09-14 23:35:01.674 I/TabSSH:SSHConnection: STEP 1: Starting connection to IP4:[PORT] as root
2026-09-14 23:35:01.677 D/TabSSH:SSHConnection: STEP 2: Checking port knock configuration
2026-09-14 23:35:01.677 D/TabSSH:SSHConnection: Port knocking disabled or no sequence configured
2026-09-14 23:35:01.678 I/TabSSH:SSHConnection: TIMING IP4: port-knock took 0 ms (total 0 ms)
2026-09-14 23:35:01.679 D/TabSSH:SSHConnection: STEP 3: Creating JSch session
2026-09-14 23:35:01.679 D/TabSSH:SSHConnection: STEP 4: Setting up host key verifier
2026-09-14 23:35:01.680 D/TabSSH:PreferenceManager: keyboard_row_count already stored as String
2026-09-14 23:35:01.680 D/TabSSH:PreferenceManager: security_auto_lock_timeout already stored as String
2026-09-14 23:35:01.680 D/TabSSH:PreferenceManager: security_clear_clipboard_timeout already stored as String
2026-09-14 23:35:01.680 I/TabSSH:SSHConnection: TIMING IP4: route-resolve took 2 ms (total 2 ms)
2026-09-14 23:35:01.681 D/TabSSH:SSHConnection: STEP 5: Checking jump host configuration
2026-09-14 23:35:01.681 I/TabSSH:SSHConnection: Direct connection to IP4 (host=IP4, ipMode=auto):22 as root
2026-09-14 23:35:01.682 I/TabSSH:SSHConnection: TIMING IP4: dns+session-create took 2 ms (total 4 ms)
2026-09-14 23:35:01.683 I/TabSSH:SSHConnection: TIMING IP4: http-socks-proxy-setup took 0 ms (total 4 ms)
2026-09-14 23:35:01.683 I/TabSSH:SSHConnection: TIMING IP4: configure-session took 1 ms (total 5 ms)
2026-09-14 23:35:01.684 I/TabSSH:SSHConnection: TIMING IP4: userinfo-setup took 0 ms (total 5 ms)
2026-09-14 23:35:01.685 I/TabSSH:SSHConnection: STEP 9: Setting up authentication
2026-09-14 23:35:01.726 I/TabSSH:SSHConnection: Agent forwarding: loaded 1/1 stored keys into JSch identity repository
2026-09-14 23:35:01.727 I/TabSSH:SSHConnection: auth=[REDACTED] SSH key authentication with keyId=69648eda-1f3e-401a-bc22-41a5b4056840
2026-09-14 23:35:01.738 I/TabSSH:SSHConnection: auth=[REDACTED] key added to JSch (keyId=69648eda-1f3e-401a-bc22-41a5b4056840)
2026-09-14 23:35:01.739 I/TabSSH:SSHConnection: TIMING IP4: auth-setup (key load/decrypt) took 64 ms (total 69 ms)
2026-09-14 23:35:01.741 I/TabSSH:SSHConnection: STEP 10: Calling server7()
2026-09-14 23:35:01.741 I/TabSSH:JSch: Connecting to IP4 port=[PORT]
2026-09-14 23:35:01.742 I/TabSSH:TimingSocketFactory: TIMING IP4: dns-resolve took 1 ms (1 address(es): 1 IPv4, 0 IPv6)
2026-09-14 23:35:01.876 I/TabSSH:TimingSocketFactory: TIMING IP4: tcp-connect (IPv4, attempt 1/1) took 134 ms
2026-09-14 23:35:01.877 I/TabSSH:JSch: Connection established
2026-09-14 23:35:01.967 I/TabSSH:JSch: Remote version string: SSH-2.0-OpenSSH_9.2p1 Debian-2+deb12u10
2026-09-14 23:35:01.968 I/TabSSH:JSch: Local version string: SSH-2.0-JSCH_2.27.7
2026-09-14 23:35:01.969 I/TabSSH:JSch: CheckCiphers: chacha20-poly1305@openssh.com
2026-09-14 23:35:01.969 I/TabSSH:JSch: CheckKexes: mlkem768x25519-sha256,mlkem768nistp256-sha256,mlkem1024nistp384-sha384,sntrup761x25519-sha512,sntrup761x25519-sha512@openssh.com,curve25519-sha256,curve25519-sha256@libssh.org,curve448-sha512
2026-09-14 23:35:02.250 D/TabSSH:SessionPersistenceManager: Activity stopped, active count: 1
2026-09-14 23:35:02.252 D/TabSSH:SessionPersistenceManager: Instance state save skipped: MainActivity
2026-09-14 23:35:03.131 I/TabSSH:JSch: CheckSignatures: ssh-ed25519,ssh-ed448
2026-09-14 23:35:03.132 D/TabSSH:JSch: server_host_key proposal before known_host reordering is: ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,rsa-sha2-512,rsa-sha2-256
2026-09-14 23:35:03.133 D/TabSSH:JSch: server_host_key proposal after known_host reordering is: ecdsa-sha2-nistp521,ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,rsa-sha2-512,rsa-sha2-256
2026-09-14 23:35:03.134 I/TabSSH:JSch: SSH_MSG_KEXINIT sent
2026-09-14 23:35:03.135 I/TabSSH:JSch: SSH_MSG_KEXINIT received
2026-09-14 23:35:03.135 I/TabSSH:JSch: Doing strict KEX
2026-09-14 23:35:03.136 I/TabSSH:JSch: server proposal: KEX algorithms: sntrup761x25519-sha512,sntrup761x25519-sha512@openssh.com,curve25519-sha256,curve25519-sha256@libssh.org,ecdh-sha2-nistp256,ecdh-sha2-nistp384,ecdh-sha2-nistp521,diffie-hellman-group-exchange-sha256,diffie-hellman-group16-sha512,diffie-hellman-group18-sha512,diffie-hellman-group14-sha256,kex-strict-s-v00@openssh.com
2026-09-14 23:35:03.137 I/TabSSH:JSch: server proposal: host key algorithms: rsa-sha2-512,rsa-sha2-256,ecdsa-sha2-nistp256,ssh-ed25519
2026-09-14 23:35:03.138 I/TabSSH:JSch: server proposal: ciphers c2s: chacha20-poly1305@openssh.com,aes128-ctr,aes192-ctr,aes256-ctr,aes128-gcm@openssh.com,aes256-gcm@openssh.com
2026-09-14 23:35:03.138 I/TabSSH:JSch: server proposal: ciphers s2c: chacha20-poly1305@openssh.com,aes128-ctr,aes192-ctr,aes256-ctr,aes128-gcm@openssh.com,aes256-gcm@openssh.com
2026-09-14 23:35:03.139 I/TabSSH:JSch: server proposal: MACs c2s: umac-64-etm@openssh.com,umac-128-etm@openssh.com,hmac-sha2-256-etm@openssh.com,hmac-sha2-512-etm@openssh.com,hmac-sha1-etm@openssh.com,umac-64@openssh.com,umac-128@openssh.com,hmac-sha2-256,hmac-sha2-512,hmac-sha1
2026-09-14 23:35:03.140 I/TabSSH:JSch: server proposal: MACs s2c: umac-64-etm@openssh.com,umac-128-etm@openssh.com,hmac-sha2-256-etm@openssh.com,hmac-sha2-512-etm@openssh.com,hmac-sha1-etm@openssh.com,umac-64@openssh.com,umac-128@openssh.com,hmac-sha2-256,hmac-sha2-512,hmac-sha1
2026-09-14 23:35:03.141 I/TabSSH:JSch: server proposal: compression c2s: none,zlib@openssh.com
2026-09-14 23:35:03.142 I/TabSSH:JSch: server proposal: compression s2c: none,zlib@openssh.com
2026-09-14 23:35:03.142 I/TabSSH:JSch: server proposal: languages c2s: 
2026-09-14 23:35:03.143 I/TabSSH:JSch: server proposal: languages s2c: 
2026-09-14 23:35:03.143 I/TabSSH:JSch: client proposal: KEX algorithms: curve25519-sha256,curve25519-sha256@libssh.org,ecdh-sha2-nistp256,ecdh-sha2-nistp384,ecdh-sha2-nistp521,diffie-hellman-group14-sha256,diffie-hellman-group16-sha512,diffie-hellman-group18-sha512,ext-info-c,kex-strict-c-v00@openssh.com
2026-09-14 23:35:03.144 I/TabSSH:JSch: client proposal: host key algorithms: ecdsa-sha2-nistp521,ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,rsa-sha2-512,rsa-sha2-256
2026-09-14 23:35:03.145 I/TabSSH:JSch: client proposal: ciphers c2s: aes256-gcm@openssh.com,aes128-gcm@openssh.com,aes256-ctr,aes192-ctr,aes128-ctr
2026-09-14 23:35:03.145 I/TabSSH:JSch: client proposal: ciphers s2c: aes256-gcm@openssh.com,aes128-gcm@openssh.com,aes256-ctr,aes192-ctr,aes128-ctr
2026-09-14 23:35:03.146 I/TabSSH:JSch: client proposal: MACs c2s: hmac-sha2-256-etm@openssh.com,hmac-sha2-512-etm@openssh.com,hmac-sha2-256,hmac-sha2-512
2026-09-14 23:35:03.147 I/TabSSH:JSch: client proposal: MACs s2c: hmac-sha2-256-etm@openssh.com,hmac-sha2-512-etm@openssh.com,hmac-sha2-256,hmac-sha2-512
2026-09-14 23:35:03.147 I/TabSSH:JSch: client proposal: compression c2s: none
2026-09-14 23:35:03.148 I/TabSSH:JSch: client proposal: compression s2c: none
2026-09-14 23:35:03.148 I/TabSSH:JSch: client proposal: languages c2s: 
2026-09-14 23:35:03.148 I/TabSSH:JSch: client proposal: languages s2c: 
2026-09-14 23:35:03.149 I/TabSSH:JSch: kex: algorithm: curve25519-sha256
2026-09-14 23:35:03.149 I/TabSSH:JSch: kex: host key algorithm: ssh-ed25519
2026-09-14 23:35:03.149 I/TabSSH:JSch: kex: server->client cipher: aes256-gcm@openssh.com MAC: <implicit> compression: none
2026-09-14 23:35:03.150 I/TabSSH:JSch: kex: client->server cipher: aes256-gcm@openssh.com MAC: <implicit> compression: none
2026-09-14 23:35:03.151 I/TabSSH:JSch: SSH_MSG_KEX_ECDH_INIT sent
2026-09-14 23:35:03.151 I/TabSSH:JSch: expecting SSH_MSG_KEX_ECDH_REPLY
2026-09-14 23:35:03.284 I/TabSSH:JSch: ssh_eddsa_verify: [SSH PUBLIC KEY] true
2026-09-14 23:35:03.285 I/TabSSH:HostKeyVerifier: HOST KEY CHECK CALLED for: IP4
2026-09-14 23:35:03.286 D/TabSSH:HostKeyVerifier: Parsed: hostname=IP4, port=[PORT]
2026-09-14 23:35:03.286 I/TabSSH:HostKeyVerifier: Checking host key for IP4:[PORT] (ssh-ed25519)
2026-09-14 23:35:03.287 I/TabSSH:HostKeyVerifier: Fingerprint: [FINGERPRINT]
2026-09-14 23:35:03.287 D/TabSSH:HostKeyVerifier: Querying database for existing host key...
2026-09-14 23:35:03.295 I/TabSSH:HostKeyVerifier: Verification result: ACCEPTED
2026-09-14 23:35:03.296 I/TabSSH:HostKeyVerifier: Host key verified: IP4:[PORT]
2026-09-14 23:35:03.298 I/TabSSH:JSch: Host 'IP4' is known and matches the EDDSA host key
2026-09-14 23:35:03.299 I/TabSSH:JSch: Reset outgoing sequence number after sending SSH_MSG_NEWKEYS for strict KEX
2026-09-14 23:35:03.300 I/TabSSH:JSch: SSH_MSG_NEWKEYS sent
2026-09-14 23:35:03.300 I/TabSSH:JSch: SSH_MSG_NEWKEYS received
2026-09-14 23:35:03.301 I/TabSSH:JSch: Reset incoming sequence number after receiving SSH_MSG_NEWKEYS for strict KEX
2026-09-14 23:35:03.301 I/TabSSH:JSch: SSH_MSG_SERVICE_REQUEST sent
2026-09-14 23:35:03.302 I/TabSSH:JSch: SSH_MSG_EXT_INFO received
2026-09-14 23:35:03.303 I/TabSSH:JSch: server-sig-algs=<ssh-ed25519,sk-ssh-ed25519@openssh.com,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,sk-ecdsa-sha2-nistp256@openssh.com,webauthn-sk-ecdsa-sha2-nistp256@openssh.com,ssh-dss,ssh-rsa,rsa-sha2-256,rsa-sha2-512>
2026-09-14 23:35:03.384 I/TabSSH:JSch: SSH_MSG_SERVICE_ACCEPT received
2026-09-14 23:35:03.470 I/TabSSH:JSch: Authentications that can continue: publickey,keyboard-interactive,password
2026-09-14 23:35:03.471 I/TabSSH:JSch: Next authentication method: publickey
2026-09-14 23:35:03.471 D/TabSSH:JSch: PubkeyAcceptedAlgorithms = ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,rsa-sha2-512,rsa-sha2-256
2026-09-14 23:35:03.472 D/TabSSH:JSch: PubkeyAcceptedAlgorithms in server-sig-algs = [ssh-ed25519, ecdsa-sha2-nistp256, ecdsa-sha2-nistp384, ecdsa-sha2-nistp521, rsa-sha2-512, rsa-sha2-256]
2026-09-14 23:35:03.561 D/TabSSH:JSch: [SSH PUBLIC KEY] success
2026-09-14 23:35:03.648 I/TabSSH:JSch: (previous line repeated 1 more times)
2026-09-14 23:35:03.648 I/TabSSH:JSch: Authentication succeeded (publickey).
2026-09-14 23:35:03.649 I/TabSSH:SSHConnection: TIMING IP4: server7 (TCP+kex+auth) took 1910 ms (total 1979 ms)
2026-09-14 23:35:03.650 I/TabSSH:SSHConnection: Successfully connected and authenticated to IP4
2026-09-14 23:35:03.651 I/TabSSH:SSHConnection: TIMING IP4: apply-advanced-settings took 1 ms (total 1980 ms)
2026-09-14 23:35:03.653 I/TabSSH:SSHConnection: Connection complete to IP4
2026-09-14 23:35:03.654 I/TabSSH:SSHSessionManager: Monitoring session connected: server3
2026-09-14 23:35:03.656 D/TabSSH:NetworkDetector: Network available
2026-09-14 23:35:03.657 D/TabSSH:ContainerSessionManager: detecting docker transport for host 11 (force=false)
2026-09-14 23:35:03.658 D/TabSSH:TransportCapabilityDetector: detect: host=11 storedMode=api_streamlocal force=false
2026-09-14 23:35:03.658 D/TabSSH:NetworkDetector: Network capabilities changed
2026-09-14 23:35:03.658 D/TabSSH:TransportCapabilityDetector: detect: host=11 engine=docker endpoint=UNIX ladder=[api_streamlocal, api_stdio, cli_exec]
2026-09-14 23:35:03.659 D/TabSSH:TransportCapabilityDetector: detect: host=11 using pinned tier api_streamlocal
2026-09-14 23:35:03.975 D/TabSSH:SshExecRunner: run: exit=0 cmdLen=279
2026-09-14 23:35:03.978 D/TabSSH:EngineSocketResolver: resolved docker socket for host 11
2026-09-14 23:35:04.065 I/TabSSH:SocketRelay: relay listening on IP8:[PORT] -> /var/run/server9
2026-09-14 23:35:04.249 I/TabSSH:TransportCapabilityDetector: api_streamlocal verified (engine 29.5.3, api 1.54)
2026-09-14 23:35:04.252 D/TabSSH:ContainerSessionManager: session sweep started
2026-09-14 23:35:04.257 I/TabSSH:ContainerSessionManager: acquired docker session for host 11 via api_streamlocal
2026-09-14 23:35:04.291 D/TabSSH:ContainerDashboardFragment: load: start hostId=11 engine=docker
2026-09-14 23:35:04.465 I/TabSSH:EngineApiTransport: negotiated Engine API version 1.43 (server=1.54, min=1.40)
2026-09-14 23:35:04.551 I/TabSSH:EngineApiTransport: (previous line repeated 6 more times)
2026-09-14 23:35:04.551 D/TabSSH:SshExecRunner: run: exit=0 cmdLen=34
2026-09-14 23:35:04.785 I/TabSSH:SshExecRunner: (previous line repeated 2 more times)
2026-09-14 23:35:04.785 D/TabSSH:SshExecRunner: run: exit=0 cmdLen=37
2026-09-14 23:35:04.849 I/TabSSH:SshExecRunner: (previous line repeated 2 more times)
2026-09-14 23:35:04.849 D/TabSSH:ContainerDashboardFragment: load: done hostId=11 info=ok version=ok
2026-09-14 23:35:09.105 D/TabSSH:SessionPersistenceManager: Activity paused: ContainerHostManagerActivity
2026-09-14 23:35:09.130 D/TabSSH:SessionPersistenceManager: Activity created: StackDetailActivity

2026-09-14 23:35:09.141 WTF/TabSSH:CRASH: ════════════════════════════════════════
2026-09-14 23:35:09.141 WTF/TabSSH:CRASH: UNCAUGHT EXCEPTION - APP CRASHED
2026-09-14 23:35:09.141 WTF/TabSSH:CRASH: Thread: main (id=2)
2026-09-14 23:35:09.141 WTF/TabSSH:CRASH: Exception: java.lang.RuntimeException
2026-09-14 23:35:09.141 WTF/TabSSH:CRASH: Message: Unable to start activity ComponentInfo{io.github.tabssh/io.github.tabssh.ui.activities.StackDetailActivity}
2026-09-14 23:35:09.141 WTF/TabSSH:CRASH: ════════════════════════════════════════
java.lang.RuntimeException: Unable to start activity ComponentInfo{io.github.tabssh/io.github.tabssh.ui.activities.StackDetailActivity}
	at android.app.ActivityThread.performLaunchActivity(ActivityThread.java:5040)
	at android.app.ActivityThread.handleLaunchActivity(ActivityThread.java:5290)
	at android.app.servertransaction.LaunchActivityItem.execute(LaunchActivityItem.java:224)
	at android.app.servertransaction.TransactionExecutor.executeNonLifecycleItem(TransactionExecutor.java:133)
	at android.app.servertransaction.TransactionExecutor.executeTransactionItems(TransactionExecutor.java:103)
	at android.app.servertransaction.TransactionExecutor.execute(TransactionExecutor.java:80)
	at android.app.ActivityThread$server10(ActivityThread.java:3287)
	at android.os.Handler.dispatchMessage(Handler.java:132)
	at android.os.Looper.dispatchMessage(Looper.java:358)
	at android.os.Looper.loopOnce(Looper.java:288)
	at android.os.Looper.loop(Looper.java:392)
	at android.app.ActivityThread.main(ActivityThread.java:10346)
	at java.lang.reflect.Method.invoke(Native Method)
	at com.android.internal.os.RuntimeInit$server11(RuntimeInit.java:638)
	at com.android.internal.os.ZygoteInit.main(ZygoteInit.java:972)
Caused by: java.lang.ClassCastException: com.google.android.material.appbar.AppBarLayout cannot be cast to com.google.android.material.appbar.MaterialToolbar
	at io.github.tabssh.ui.activities.StackDetailActivity.onCreate(r8-map-id-7ec68b500997785028d204899adbe95bcb9b124b5cb9eca0f898eabd09e5288a:106)
	at android.app.Activity.performCreate(Activity.java:9758)
	at android.app.Activity.performCreate(Activity.java:9727)
	at android.app.Instrumentation.callActivityOnCreate(Instrumentation.java:1544)
	at android.app.ActivityThread.performLaunchActivity(ActivityThread.java:5024)
	... 14 more

2026-09-14 23:35:09.141 WTF/TabSSH:CRASH: ════════════════════════════════════════

2026-09-14 23:35:09.147 E/TabSSH:TabSSHApplication: Uncaught exception in thread main
java.lang.RuntimeException: Unable to start activity ComponentInfo{io.github.tabssh/io.github.tabssh.ui.activities.StackDetailActivity}
	at android.app.ActivityThread.performLaunchActivity(ActivityThread.java:5040)
	at android.app.ActivityThread.handleLaunchActivity(ActivityThread.java:5290)
	at android.app.servertransaction.LaunchActivityItem.execute(LaunchActivityItem.java:224)
	at android.app.servertransaction.TransactionExecutor.executeNonLifecycleItem(TransactionExecutor.java:133)
	at android.app.servertransaction.TransactionExecutor.executeTransactionItems(TransactionExecutor.java:103)
	at android.app.servertransaction.TransactionExecutor.execute(TransactionExecutor.java:80)
	at android.app.ActivityThread$server10(ActivityThread.java:3287)
	at android.os.Handler.dispatchMessage(Handler.java:132)
	at android.os.Looper.dispatchMessage(Looper.java:358)
	at android.os.Looper.loopOnce(Looper.java:288)
	at android.os.Looper.loop(Looper.java:392)
	at android.app.ActivityThread.main(ActivityThread.java:10346)
	at java.lang.reflect.Method.invoke(Native Method)
	at com.android.internal.os.RuntimeInit$server11(RuntimeInit.java:638)
	at com.android.internal.os.ZygoteInit.main(ZygoteInit.java:972)
Caused by: java.lang.ClassCastException: com.google.android.material.appbar.AppBarLayout cannot be cast to com.google.android.material.appbar.MaterialToolbar
	at io.github.tabssh.ui.activities.StackDetailActivity.onCreate(r8-map-id-7ec68b500997785028d204899adbe95bcb9b124b5cb9eca0f898eabd09e5288a:106)
	at android.app.Activity.performCreate(Activity.java:9758)
	at android.app.Activity.performCreate(Activity.java:9727)
	at android.app.Instrumentation.callActivityOnCreate(Instrumentation.java:1544)
	at android.app.ActivityThread.performLaunchActivity(ActivityThread.java:5024)
	... 14 more

2026-09-14 23:35:23.652 D/TabSSH:SessionPersistenceManager: Saving session state for 1 tabs
2026-09-14 23:35:23.671 I/TabSSH:SessionPersistenceManager: Saved session state for 1 tabs
2026-09-14 23:35:38.343 I/TabSSH:Logger: === TabSSH Debug Logging Started ===
2026-09-14 23:35:38.344 I/TabSSH:Logger: App Version: 1.0.0 (11)
2026-09-14 23:35:38.345 I/TabSSH:Logger: Android: 16 (API 36)
2026-09-14 23:35:38.345 I/TabSSH:Logger: Device: samsung SM-X230
2026-09-14 23:35:38.346 I/TabSSH:AnrWatchdog: ANR watchdog started (timeout=5000ms)
2026-09-14 23:35:38.347 D/TabSSH:TabSSHApplication: Application starting...
2026-09-14 23:35:38.347 D/TabSSH:TabSSHApplication: Applied saved theme: dark (mode=2)
2026-09-14 23:35:38.348 D/TabSSH:TabSSHApplication: Applied saved language: en
2026-09-14 23:35:38.360 D/TabSSH:NotificationHelper: Registered 10 notification channels: Session Service, Active Sessions, Session Alerts, File Transfers, Connection Errors, Host Monitoring Alerts, Performance Alerts, Container Update Alerts, Renewal Reminders, Session Recording
2026-09-14 23:35:38.362 D/TabSSH:PreferenceManager: Initialized with 138 preferences
2026-09-14 23:35:38.364 D/TabSSH:PreferenceManager: keyboard_row_count already stored as String
2026-09-14 23:35:38.365 D/TabSSH:PreferenceManager: security_auto_lock_timeout already stored as String
2026-09-14 23:35:38.366 D/TabSSH:PreferenceManager: security_clear_clipboard_timeout already stored as String
2026-09-14 23:35:38.366 D/TabSSH:ThemeManager: Initializing theme manager
2026-09-14 23:35:38.371 D/TabSSH:ThemeManager: Installing built-in themes
2026-09-14 23:35:38.380 D/TabSSH:SecurePasswordManager: Initialized with Android Keystore
2026-09-14 23:35:38.381 D/TabSSH:KeyStorage: Initialized with Android Keystore
2026-09-14 23:35:38.382 D/TabSSH:SSHSessionManager: Initializing SSH session manager
2026-09-14 23:35:38.382 I/TabSSH:SSHSessionManager: SSH session manager initialized
2026-09-14 23:35:38.383 D/TabSSH:PerformanceManager: Performance manager initialized
2026-09-14 23:35:38.384 I/TabSSH:PerformanceManager: Battery state changed: level=74%, powerSave=true, optimization=AGGRESSIVE
2026-09-14 23:35:38.385 D/TabSSH:NetworkOptimizer: Set keep-alive interval to 300s for optimization level AGGRESSIVE
2026-09-14 23:35:38.385 I/TabSSH:PerformanceManager: Performance optimizations applied
2026-09-14 23:35:38.393 D/TabSSH:HostAvailabilityWorker: Periodic availability check scheduled (15 min, network + not-low-battery)
2026-09-14 23:35:38.394 D/TabSSH:ThemeManager: Loading available themes
2026-09-14 23:35:38.402 D/TabSSH:ContainerUpdateCheckWorker: Periodic container update check scheduled (12 h, network + not-low-battery)
2026-09-14 23:35:38.403 D/TabSSH:RenewalReminderWorker: Periodic renewal reminder check scheduled (1 day, not-low-battery)
2026-09-14 23:35:38.403 D/TabSSH:SessionPersistenceManager: Session persistence manager initialized
2026-09-14 23:35:38.410 D/TabSSH:TabSSHApplication: Core components initialized
2026-09-14 23:35:38.420 D/TabSSH:PreferenceManager: keyboard_row_count already stored as String
2026-09-14 23:35:38.421 D/TabSSH:PreferenceManager: security_auto_lock_timeout already stored as String
2026-09-14 23:35:38.421 D/TabSSH:PreferenceManager: security_clear_clipboard_timeout already stored as String
2026-09-14 23:35:38.421 D/TabSSH:ThemeManager: Loaded 22 themes
2026-09-14 23:35:38.422 D/TabSSH:ThemeManager: Current theme: Dracula
2026-09-14 23:35:38.422 I/TabSSH:ThemeManager: Theme manager initialized with 22 themes
2026-09-14 23:35:38.423 D/TabSSH:SyncWorkScheduler: Scheduled periodic sync every 60 minutes
2026-09-14 23:35:38.423 I/TabSSH:TabSSHApplication: Application initialized successfully (background)
2026-09-14 23:35:38.452 D/TabSSH:MainActivity: onCreate - New 5-tab layout
2026-09-14 23:35:38.461 D/TabSSH:MainActivity: Startup behavior: last_tab → tab 3
2026-09-14 23:35:38.462 I/TabSSH:MainActivity: MainActivity created successfully
2026-09-14 23:35:38.463 I/TabSSH:SessionPersistenceManager: App foregrounded after 0ms (coldStart=true)
2026-09-14 23:35:38.464 D/TabSSH:SessionPersistenceManager: Activity started, active count: 1
2026-09-14 23:35:38.465 D/TabSSH:SessionPersistenceManager: Activity resumed: MainActivity
2026-09-14 23:35:38.472 D/TabSSH:SessionPersistenceManager: Restoring 1 saved sessions
2026-09-14 23:35:38.477 D/TabSSH:SSHTab: Created tab server1
2026-09-14 23:35:38.478 I/TabSSH:TermuxBridge: Initializing Termux emulator 80x24
2026-09-14 23:35:38.485 I/TabSSH:TermuxBridge: Termux emulator initialized
2026-09-14 23:35:38.485 D/TabSSH:TabManager: Created new tab: server1
2026-09-14 23:35:38.486 D/TabSSH:TermuxBridge: Resized to 90x37
2026-09-14 23:35:38.499 D/TabSSH:SessionPersistenceManager: Restored terminal state for tab: ⏸ user1@server2
2026-09-14 23:35:38.499 I/TabSSH:SessionPersistenceManager: Restored 1 of 1 sessions
2026-09-14 23:35:38.594 I/TabSSH:PortForwardCoordinator: Auto-starting 0 port forward(s)

