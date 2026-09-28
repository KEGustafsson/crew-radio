# Crew Radio — running your own Reticulum hub

How to put up the Reticulum transport node that the crew ashore connects to: what to run it on,
how to install and configure it, how to make it start at boot, and how to point the phones and
the Signal K plugin at it. The [README](../README.md#from-ashore-reticulum) says what the
**RETICULUM** tile does; [SECURITY.md](SECURITY.md#reticulum-optional-off-by-default) says what a
transport node can and cannot see.

The hub is Reticulum's own daemon, `rnsd`, from the reference implementation (Python, MIT
licence). Crew Radio includes none of its code; the app and the plugin only speak its protocol.
Everything below was checked against `rnsd` 1.5.4. The Reticulum manual
(<https://reticulum.network/manual/>) covers the rest: other platforms, radio interfaces, and
every option.

## 1. Do you need one?

The phones ashore and the boat have to meet somewhere both can reach. That is the hub.

| Setup | What it needs | Notes |
|---|---|---|
| **A hub ashore** (recommended) | A small machine that is always on and reachable from the internet on one TCP port: a cheap cloud server (VPS), or a Raspberry Pi at home behind a port forward | The phones ashore and the boat both dial *out* to it, so the boat works behind a mobile router's carrier-grade NAT. |
| **The boat is the hub** | The boat's own `rnsd`, reachable from the internet | Rarely possible: a boat on mobile data almost never has a public address. |
| **A public community hub** | Nothing to run | Someone else's machine, their uptime and their rules. It sees what any hub sees: encrypted traffic, addresses and timing. Many use interface access codes (a network name or passphrase), which Crew Radio does not support. |

Voice needs an internet-class link. Reticulum over LoRa is far too slow for it, so a hub for Crew
Radio is a TCP one.

## 2. What to run it on

- Linux with Python 3 and systemd: Debian, Ubuntu or Raspberry Pi OS. The Reticulum manual
  recommends a 64-bit Raspberry Pi OS, because 32-bit versions do not always have packages for
  its dependencies.
- Very little CPU and memory. A Raspberry Pi 3, or the smallest VPS on offer, is plenty.
- One TCP port reachable from the internet. 4242 is the usual one and the app's default. If
  your home address changes, you also need a DNS name that follows it (dynamic DNS).

The commands below are for Debian, Ubuntu and Raspberry Pi OS. For another system, see the
manual's *Platform-Specific Install Notes*.

## 3. Install

Run `rnsd` as its own system user, not as your login and never as root. It lives in a Python
virtual environment in that user's home, `/var/lib/rns`, which also keeps it clear of the
system's Python packages (recent Debian and Ubuntu refuse a plain `pip install` there).

```sh
sudo apt update
sudo apt install python3 python3-venv
sudo useradd --system --create-home --home-dir /var/lib/rns --shell /usr/sbin/nologin rns
sudo -u rns python3 -m venv /var/lib/rns/venv
sudo -u rns /var/lib/rns/venv/bin/pip install rns
sudo -u rns /var/lib/rns/venv/bin/rnsd --version
```

The last line should print `rnsd 1.5.4` or later.

To update it later:

```sh
sudo -u rns /var/lib/rns/venv/bin/pip install --upgrade rns
sudo systemctl restart rnsd
```

## 4. Configure the hub

`rnsd` reads `~/.reticulum/config`, which for the `rns` user is `/var/lib/rns/.reticulum/config`.
Write the whole file yourself:

```sh
sudo -u rns mkdir -p /var/lib/rns/.reticulum
sudo -u rns nano /var/lib/rns/.reticulum/config
```

```text
[reticulum]
  # Required: pass packets between the clients. Without it, two phones connected to the same
  # hub never hear each other (checked against rnsd 1.5.4).
  enable_transport = Yes
  # Lets rnstatus look at the running daemon.
  share_instance = Yes

[logging]
  # 4 = notices. Raise it to 6 or 7 while chasing a problem.
  loglevel = 4

[interfaces]
  [[Crew Radio hub]]
    type = TCPServerInterface
    enabled = yes
    listen_ip = 0.0.0.0
    listen_port = 4242
```

- **No network name or passphrase** on this interface (`network_name`, `passphrase`). Those are
  Reticulum's interface access codes, and Crew Radio does not support them. The channel key is
  what keeps strangers out of the channel.
- **No `AutoInterface`** on a cloud server. It finds Reticulum nodes on the local network,
  which a VPS does not have. On a Raspberry Pi at home it is harmless. If you want it there,
  add it back:

  ```text
    [[Default Interface]]
      type = AutoInterface
      enabled = yes
  ```
- `listen_ip = 0.0.0.0` listens on every IPv4 address. The manual covers IPv6 (`prefer_ipv6`)
  and binding to one interface (`device = eth0`).

### Open the port

- **The machine's firewall.** With `ufw`:

  ```sh
  sudo ufw allow 4242/tcp
  ```

- **A cloud server** usually has a firewall of its own in the provider's console ("security
  group", "firewall rules"). Allow inbound TCP 4242 there as well.
- **At home**, forward TCP 4242 on the router to the Pi's address, and give the Pi a fixed
  address on the local network (a DHCP reservation) so the forward keeps pointing at it.

## 5. Start at boot

Create `/etc/systemd/system/rnsd.service`:

```sh
sudo nano /etc/systemd/system/rnsd.service
```

```text
[Unit]
Description=Reticulum transport node (Crew Radio hub)
Wants=network-online.target
After=network-online.target

[Service]
Type=simple
User=rns
ExecStart=/var/lib/rns/venv/bin/rnsd --service
Restart=always
RestartSec=3
# rnsd needs nothing outside its own home.
NoNewPrivileges=yes
PrivateTmp=yes
ProtectSystem=strict
ReadWritePaths=/var/lib/rns

[Install]
WantedBy=multi-user.target
```

Then enable it and start it now:

```sh
sudo systemctl daemon-reload
sudo systemctl enable --now rnsd
systemctl status rnsd
```

`enable` is what makes it start at every boot, and `Restart=always` restarts it if it ever
stops. Reboot once (`sudo reboot`) and check that `systemctl status rnsd` says *active (running)*
without you doing anything.

With `--service`, `rnsd` writes its log to `/var/lib/rns/.reticulum/logfile`, not to the
journal:

```sh
sudo tail -f /var/lib/rns/.reticulum/logfile
```

To see the hub at work:

```sh
sudo -u rns /var/lib/rns/venv/bin/rnstatus
```

It lists the `TCPServerInterface[Crew Radio hub/0.0.0.0:4242]` with its `Clients` count, and near
the end says `Transport Instance <…> running` (followed by the uptime). If it says nothing about a
transport instance, `enable_transport` is not on.

## 6. Connect the crew

- **A phone ashore.** Tap the **RETICULUM** tile. The first time, it asks for the transport node:
  type the hub as `hub.example.org` or `hub.example.org:4242` (4242 is used when the port is
  left out). It can be changed later in **Settings › Transport node**, in the Reticulum section.
  Join the channel as usual. The status line says *Reticulum: connected to hub.example.org:4242*,
  then *Reticulum: 1 link* once another Crew Radio node on the hub has answered, and the roster
  lists that node with **Reticulum** among its links.
- **The boat.** In the signalk-crewradio plugin's settings, under **Reticulum**, tick **Enabled**
  and set **Transport node host** to the hub (`hub.example.org`) and **Transport node port** to
  4242. The plugin dials out over whatever internet
  the boat has, and relays between Reticulum and the boat's WLAN, so the phones aboard need
  nothing new.
- **Several crews** can share one hub. Each channel key makes its own Reticulum destination, and
  a node links only to nodes with the same key.

### A Reticulum node on the boat as well (optional)

The plugin's default transport node, `127.0.0.1:4242`, is an `rnsd` on the Signal K server
itself. Run one there if the boat has other Reticulum programs or a LoRa RNode. Install it as in
sections 3 and 5, and give it this config, which connects it onward to the hub:

```text
[reticulum]
  enable_transport = Yes
  share_instance = Yes

[logging]
  loglevel = 4

[interfaces]
  [[Default Interface]]
    type = AutoInterface
    enabled = yes

  # For the plugin on this machine. 127.0.0.1 keeps it off the boat's network.
  [[Crew Radio boat]]
    type = TCPServerInterface
    enabled = yes
    listen_ip = 127.0.0.1
    listen_port = 4242

  # Out to the hub ashore. Reconnects by itself when the boat's internet comes back.
  [[Shore hub]]
    type = TCPClientInterface
    enabled = yes
    target_host = hub.example.org
    target_port = 4242
```

Leave the plugin's transport node at `127.0.0.1:4242`. The path is then plugin → boat `rnsd` →
hub → phone ashore, which was checked end to end with two `rnsd` 1.5.4 instances.

## 7. When it does not work

| What you see | Look at |
|---|---|
| The phone says *Reticulum: can't reach …* | The port is not reachable. From a network outside, e.g. a phone on mobile data with a terminal app, or another server, run `nc -vz hub.example.org 4242`. Check the machine's firewall, the provider's firewall, the router's port forward, and that `rnstatus` shows the interface. |
| The phone stays at *Reticulum: connected to …* and never shows *1 link* (the plugin's status: *Reticulum 0 links*) for more than a minute | The hub's `enable_transport` is off (`rnstatus` shows no transport instance), or the other node has a different channel key (a link then never gets past the key proof), or it is not connected at all (compare the `Clients` count). |
| After the hub restarts or the boat's internet comes back, the phones and the plugin take a while to find each other | Up to half a minute is normal: a node with somebody missing announces every 30 seconds, and answers the others' announces at once. Much longer: compare the clocks (the plugin logs *Clock: … packets more than 60 s off*, and the phone's Status screen counts them as *stale*), since a clock more than a minute out drops every packet even over a working link. |
| It stops after a reboot | `systemctl is-enabled rnsd` should say `enabled`. Then look at `systemctl status rnsd` and the log file. |
| `rnstatus` says no shared instance is running | `rnsd` is not running, or `share_instance` is off, or you ran `rnstatus` as another user than `rns`. |
| Anything else | Set `loglevel = 6`, restart, and read the log file. |

## 8. Looking after it

- The hub carries only encrypted traffic: every channel packet is sealed with the channel key end
  to end, and Reticulum's link encryption wraps it again. It sees who connects (IP addresses),
  when, and how much, and a destination named after an HMAC of the channel key.
- A Reticulum TCP interface is open to anyone who finds it: strangers can connect and use it as a
  transport, and link to the crew's nodes. A link carries nothing until its far end has proved the
  channel key with a proof bound to that link, so they get nothing, and a link that has proved the
  key is never pushed out. A stranger flooding the hub with link requests can keep new links from
  forming while it lasts; links already up are not affected. If that bothers you, restrict the
  port in the firewall to the addresses you expect. Phones on mobile data change address, so
  that usually means the boat only.
- Keep the machine patched (`sudo apt install unattended-upgrades` on Debian, Ubuntu and
  Raspberry Pi OS), and update `rns` now and then (section 3).
