package io.github.tabssh.pairing

import android.content.Context
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.ssh.auth.AuthType
import io.github.tabssh.storage.database.entities.ConnectionGroup
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.database.entities.Identity
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * Convert a decrypted [PairingPayload] into Room rows and insert via the
 * existing DAOs. Returns counts so the UI can show "Imported N connections,
 * M groups, K identities".
 *
 * - Groups and identities are inserted by name with conflict-resolve: if a
 *   group/identity with the same name already exists, we reuse its ID
 *   instead of inserting a duplicate.
 * - Connections always get a fresh UUID — never overwrite an existing
 *   connection by ID, even if the desktop side had one. This prevents a
 *   maliciously-crafted QR from clobbering existing connections.
 * - No password / private-key import. Those have to be (re)entered by the
 *   user after import.
 */
object PairingImporter {

    data class ImportSummary(
        val connections: Int,
        val groups: Int,
        val identities: Int,
        val skipped: List<String>,
    )

    suspend fun import(context: Context, payload: PairingPayload): ImportSummary {
        val app = context.applicationContext as TabSSHApplication
        val db = app.database

        val connectionDao = db.connectionDao()
        val groupDao = db.connectionGroupDao()
        val identityDao = db.identityDao()

        // Pre-load existing groups + identities so we can resolve names
        // without inserting duplicates.
        val existingGroups: Map<String, String> = groupDao.getAllGroups().first()
            .associate { it.name to it.id }
            .toMutableMap()
        val existingIdentities: Map<String, String> = identityDao.getAllIdentities().first()
            .associate { it.name to it.id }
            .toMutableMap()

        val groupsByName = HashMap(existingGroups)
        val identitiesByName = HashMap(existingIdentities)

        var groupsCreated = 0
        var identitiesCreated = 0
        val skipped = mutableListOf<String>()

        // Insert any groups that don't already exist.
        val groupsToInsert = payload.groups.filter { it.name !in groupsByName }
        if (groupsToInsert.isNotEmpty()) {
            val rows = groupsToInsert.map { exported ->
                val id = UUID.randomUUID().toString()
                groupsByName[exported.name] = id
                groupsCreated++
                ConnectionGroup(
                    id = id,
                    name = exported.name,
                    parentId = null,        // parent linkage by name is v2; v1 is flat
                    icon = exported.icon,
                    color = exported.color,
                    sortOrder = exported.sortOrder,
                )
            }
            groupDao.insertGroups(rows)
        }

        // Insert any identities that don't already exist.
        val identitiesToInsert = payload.identities.filter { it.name !in identitiesByName }
        if (identitiesToInsert.isNotEmpty()) {
            val rows = identitiesToInsert.map { exported ->
                val id = UUID.randomUUID().toString()
                identitiesByName[exported.name] = id
                identitiesCreated++
                Identity(
                    id = id,
                    name = exported.name,
                    username = exported.username,
                    authType = AuthType.fromString(exported.authType),
                    keyId = null,            // user re-associates a key after import
                    password = null,         // never carried over the wire
                    description = exported.description,
                )
            }
            identityDao.insertAll(rows)
        }

        // Insert the connections — always fresh UUIDs.
        var connectionsImported = 0
        if (payload.connections.isNotEmpty()) {
            val rows = payload.connections.mapNotNull { exported ->
                try {
                    ConnectionProfile(
                        id = UUID.randomUUID().toString(),
                        name = exported.name,
                        host = exported.host,
                        port = exported.port,
                        username = exported.username,
                        protocol = exported.protocol,
                        authType = exported.authType,
                        keyId = null,                        // user re-associates after import
                        savePassword = false,
                        terminalType = exported.terminalType,
                        encoding = exported.encoding,
                        compression = exported.compression,
                        keepAlive = exported.keepAlive,
                        x11Forwarding = exported.x11Forwarding,
                        useMosh = exported.useMosh,
                        proxyHost = exported.proxyHost,
                        proxyPort = exported.proxyPort,
                        proxyType = exported.proxyType,
                        proxyUsername = exported.proxyUsername,
                        proxyAuthType = exported.proxyAuthType,
                        proxyKeyId = null,
                        identityId = exported.identityName?.let { identitiesByName[it] },
                        theme = exported.theme,
                        postConnectScript = exported.postConnectScript,
                        envVars = exported.envVars,
                        agentForwarding = exported.agentForwarding,
                        colorTag = exported.colorTag,
                        groupId = exported.groupName?.let { groupsByName[it] },
                    )
                } catch (e: Exception) {
                    Logger.w("PairingImporter", "Skipped connection '${exported.name}': ${e.message}")
                    skipped.add(exported.name)
                    null
                }
            }
            connectionDao.insertConnections(rows)
            connectionsImported = rows.size
        }

        Logger.i(
            "PairingImporter",
            "Imported $connectionsImported connections, $groupsCreated groups, $identitiesCreated identities" +
                if (skipped.isEmpty()) "" else " (skipped: ${skipped.joinToString(", ")})"
        )

        return ImportSummary(
            connections = connectionsImported,
            groups = groupsCreated,
            identities = identitiesCreated,
            skipped = skipped,
        )
    }
}
