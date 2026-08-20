package io.github.tabssh.containers.runconfig

import org.json.JSONObject

/**
 * Hand-written, realistic `docker inspect` fixture JSON shared by the
 * runconfig test classes: an nginx-like container with published tcp+udp
 * ports, bind + named-volume mounts, env vars, labels, a restart policy,
 * caps, a device, tmpfs, and a user-defined network — the representative
 * shape the translator and recreate planner must handle.
 */
object InspectFixtures {

    const val NGINX_INSPECT = """
    {
      "Id": "3f4a9c1b2d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f8",
      "Created": "2026-08-01T12:34:56.789Z",
      "Name": "/web",
      "State": {
        "Status": "running",
        "Running": true
      },
      "Config": {
        "Hostname": "web-internal",
        "User": "101:101",
        "AttachStdin": false,
        "ExposedPorts": {
          "80/tcp": {},
          "53/udp": {}
        },
        "Env": [
          "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
          "NGINX_VERSION=1.27.3",
          "APP_MOTD=hello world"
        ],
        "Cmd": [
          "nginx",
          "-g",
          "daemon off;"
        ],
        "Image": "nginx:1.27",
        "WorkingDir": "/usr/share/nginx/html",
        "Entrypoint": [
          "/docker-entrypoint.sh"
        ],
        "Labels": {
          "maintainer": "NGINX Docker Maintainers <docker-maint@nginx.com>",
          "com.example.tier": "frontend"
        }
      },
      "HostConfig": {
        "Binds": [
          "/srv/web/html:/usr/share/nginx/html:ro",
          "webdata:/var/cache/nginx"
        ],
        "NetworkMode": "webnet",
        "PortBindings": {
          "80/tcp": [
            {
              "HostIp": "",
              "HostPort": "8080"
            }
          ],
          "53/udp": [
            {
              "HostIp": "127.0.0.1",
              "HostPort": "5353"
            }
          ]
        },
        "RestartPolicy": {
          "Name": "on-failure",
          "MaximumRetryCount": 3
        },
        "CapAdd": [
          "NET_ADMIN"
        ],
        "CapDrop": [
          "MKNOD"
        ],
        "Privileged": false,
        "Devices": [
          {
            "PathOnHost": "/dev/fuse",
            "PathInContainer": "/dev/fuse",
            "CgroupPermissions": "rwm"
          }
        ],
        "Tmpfs": {
          "/run": "rw,size=64m"
        },
        "ShmSize": 67108864
      },
      "NetworkSettings": {
        "Networks": {
          "webnet": {
            "IPAMConfig": null,
            "Aliases": [
              "web"
            ],
            "NetworkID": "9ab8c7d6e5f40312",
            "EndpointID": "1a2b3c4d5e6f7089",
            "Gateway": "172.20.0.1",
            "IPAddress": "172.20.0.5",
            "MacAddress": "02:42:ac:14:00:05"
          }
        }
      }
    }
    """

    /** Fresh parse of [NGINX_INSPECT] so tests can mutate freely. */
    fun nginx(): JSONObject = JSONObject(NGINX_INSPECT)
}
