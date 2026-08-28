package io.github.hectorvent.floci.services.transfer;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.transfer.model.Server;
import io.github.hectorvent.floci.services.transfer.model.SshPublicKey;
import io.github.hectorvent.floci.services.transfer.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class TransferServiceTest {

    private static final String REGION = "us-east-1";

    private TransferService service;

    @BeforeEach
    void setUp() {
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        when(storageFactory.create(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(AccountAwareStorageBackend.inMemory("000000000000"));

        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        RegionResolver regionResolver = new RegionResolver(REGION, "000000000000");
        service = new TransferService(storageFactory, config, regionResolver);
    }

    private Server createServer() {
        return service.createServer(REGION, List.of("SFTP"), null, null, null, null, null, null, null);
    }

    @Test
    void createServerComesUpOnlineWithDefaults() {
        Server server = createServer();

        assertEquals("ONLINE", server.getState());
        assertEquals(List.of("SFTP"), server.getProtocols());
        assertEquals("PUBLIC", server.getEndpointType());
        assertEquals("SERVICE_MANAGED", server.getIdentityProviderType());
        assertTrue(server.getServerId().startsWith("s-"));
        assertTrue(server.getArn().contains(":transfer:"));
    }

    @Test
    void deleteServerRejectsWhileOnline() {
        Server server = createServer();
        assertThrows(AwsException.class, () -> service.deleteServer(server.getServerId()));
    }

    @Test
    void deleteServerSucceedsOnceOffline() {
        Server server = createServer();
        service.stopServer(server.getServerId());
        service.deleteServer(server.getServerId());

        assertThrows(AwsException.class, () -> service.getServer(server.getServerId()));
    }

    @Test
    void stopThenStartRoundTripsState() {
        Server server = createServer();
        Server stopped = service.stopServer(server.getServerId());
        assertEquals("OFFLINE", stopped.getState());

        Server started = service.startServer(server.getServerId());
        assertEquals("ONLINE", started.getState());
    }

    @Test
    void startServerRejectsWhenAlreadyOnline() {
        Server server = createServer();
        assertThrows(AwsException.class, () -> service.startServer(server.getServerId()));
    }

    @Test
    void createUserRejectsUnknownServer() {
        assertThrows(AwsException.class,
                () -> service.createUser("s-does-not-exist", REGION, "alice", "role", null, null, null, null));
    }

    @Test
    void createUserRejectsDuplicateName() {
        Server server = createServer();
        service.createUser(server.getServerId(), REGION, "alice", "role", null, null, null, null);

        assertThrows(AwsException.class,
                () -> service.createUser(server.getServerId(), REGION, "alice", "role", null, null, null, null));
    }

    @Test
    void deleteServerCascadesItsUsers() {
        Server server = createServer();
        service.createUser(server.getServerId(), REGION, "alice", "role", null, null, null, null);
        service.stopServer(server.getServerId());

        service.deleteServer(server.getServerId());

        assertThrows(AwsException.class, () -> service.getUser(server.getServerId(), "alice"));
    }

    @Test
    void sshPublicKeyImportAndDeleteRoundTrip() {
        Server server = createServer();
        service.createUser(server.getServerId(), REGION, "alice", "role", null, null, null, null);

        SshPublicKey key = service.importSshPublicKey(server.getServerId(), "alice", "ssh-rsa AAAA...");
        User withKey = service.getUser(server.getServerId(), "alice");
        assertEquals(1, withKey.getSshPublicKeys().size());

        service.deleteSshPublicKey(server.getServerId(), "alice", key.getSshPublicKeyId());
        User withoutKey = service.getUser(server.getServerId(), "alice");
        assertTrue(withoutKey.getSshPublicKeys().isEmpty());
    }

    @Test
    void deleteSshPublicKeyRejectsUnknownKeyId() {
        Server server = createServer();
        service.createUser(server.getServerId(), REGION, "alice", "role", null, null, null, null);

        assertThrows(AwsException.class,
                () -> service.deleteSshPublicKey(server.getServerId(), "alice", "key-does-not-exist"));
    }

    @Test
    void tagResourceSyncsOntoServer() {
        Server server = createServer();
        service.tagResource(server.getArn(), Map.of("Env", "test"));

        Server tagged = service.getServer(server.getServerId());
        assertEquals("test", tagged.getTags().get("Env"));
        assertEquals(Map.of("Env", "test"), service.listTagsForResource(server.getArn()));
    }

    @Test
    void untagResourceRemovesFromServer() {
        Server server = createServer();
        service.tagResource(server.getArn(), Map.of("Env", "test"));
        service.untagResource(server.getArn(), List.of("Env"));

        assertTrue(service.listTagsForResource(server.getArn()).isEmpty());
    }
}
