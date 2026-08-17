package nu.miguel.persona.editor;

import nu.miguel.persona.editor.protocol.SessionCreateResponse;
import nu.miguel.persona.editor.protocol.ContentFile;
import nu.miguel.persona.editor.protocol.DraftSaveRequest;
import nu.miguel.persona.editor.protocol.Protocol;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.net.URI;
import org.bukkit.command.CommandSender;
import nu.miguel.persona.editor.protocol.Capability;
import nu.miguel.persona.editor.protocol.SessionRestrictions;
import nu.miguel.persona.editor.protocol.LiveStateSnapshot;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorClientJsonTest {
    @Test void decodesBackendInstantWithoutServiceLoaderDiscovery() throws Exception {
        String body = """
                {"sessionId":"173a8e5b-3fd8-4b21-a8cd-c07449bde772",
                 "editorUrl":"http://localhost/editor/session/1",
                 "verificationCode":"ABCDEFGH2345",
                 "pluginSocketUrl":"ws://localhost/ws/v1/plugin?session=1",
                 "pluginLeaseToken":"lease",
                 "expiresAt":"2026-08-17T09:00:00Z"}
                """;

        SessionCreateResponse response = EditorClient.jsonMapper().readValue(body, SessionCreateResponse.class);

        assertEquals(Instant.parse("2026-08-17T09:00:00Z"), response.expiresAt());
    }

    @Test void reconnectCarriesLastReceivedSequenceWithoutDroppingSessionQuery() {
        assertEquals("wss://editor.example/ws/v1/plugin?session=abc&after=42",
                EditorClient.reconnectUri(URI.create("wss://editor.example/ws/v1/plugin?session=abc"), 42).toString());
        assertThrows(IllegalArgumentException.class,
                () -> EditorClient.reconnectUri(URI.create("wss://editor.example/ws"), -1));
    }

    @Test void serializesDraftYamlVerbatimIncludingCommentsAndExtensionData() throws Exception {
        String yaml = "# author note\nid: test:tree\nextension-owned:\n  future: true\n";
        DraftSaveRequest draft = new DraftSaveRequest(Protocol.VERSION, "a".repeat(64), java.util.List.of(
                new ContentFile("behaviors/tree.yml", "b".repeat(64), yaml)));

        String encoded = EditorClient.jsonMapper().writeValueAsString(draft);
        DraftSaveRequest decoded = EditorClient.jsonMapper().readValue(encoded, DraftSaveRequest.class);

        assertEquals(yaml, decoded.files().getFirst().content());
        assertTrue(encoded.contains("extension-owned"));
    }

    @Test void mapsIndependentBukkitPermissionsToRequestedCapabilities() {
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission("persona.admin.editor.players")).thenReturn(true);
        when(sender.hasPermission("persona.admin.editor.memories")).thenReturn(false);
        when(sender.hasPermission("persona.admin.editor.drafts")).thenReturn(true);
        when(sender.hasPermission("persona.admin.editor.publish")).thenReturn(false);
        when(sender.hasPermission("persona.admin.editor.mutate")).thenReturn(true);

        assertEquals(java.util.Set.of(Capability.CONTENT_VIEW, Capability.PLAYER_VIEW,
                        Capability.DRAFT_EDIT, Capability.LIVE_MUTATE),
                EditorClient.requestedCapabilities(sender));
    }

    @Test void sessionRestrictionsRoundTripAsProtocolOnlyData() throws Exception {
        SessionRestrictions restrictions = new SessionRestrictions(java.util.Set.of("world_nether"),
                java.util.Set.of("b0000000-0000-0000-0000-000000000001"),
                java.util.Set.of("story:keeper"), java.util.Set.of("story"));

        String encoded = EditorClient.jsonMapper().writeValueAsString(restrictions);
        SessionRestrictions decoded = EditorClient.jsonMapper().readValue(encoded, SessionRestrictions.class);

        assertEquals(restrictions, decoded);
        assertTrue(encoded.contains("story:keeper"));
        assertTrue(!encoded.contains("inventory") && !encoded.contains("chat") && !encoded.contains("address"));
    }

    @Test void liveDeltaContainsOnlyChangesAndExplicitRemovals(){
        java.util.UUID subscription=java.util.UUID.randomUUID(),firstPlayer=java.util.UUID.randomUUID(),secondPlayer=java.util.UUID.randomUUID();
        LiveStateSnapshot before=live(subscription,java.util.List.of(new LiveStateSnapshot.Player(firstPlayer,"world",java.util.List.of(),0),new LiveStateSnapshot.Player(secondPlayer,"world",java.util.List.of(),0)));
        LiveStateSnapshot after=live(subscription,java.util.List.of(new LiveStateSnapshot.Player(firstPlayer,"world_nether",java.util.List.of("story:q"),1)));
        LiveStateSnapshot delta=EditorClient.delta(before,after,2);
        assertEquals(1,delta.players().size());assertEquals(firstPlayer,delta.players().getFirst().playerId());
        assertEquals(java.util.List.of("player:"+secondPlayer),delta.removedKeys());assertTrue(!delta.full());assertEquals(2,delta.revision());
    }

    private static LiveStateSnapshot live(java.util.UUID id,java.util.List<LiveStateSnapshot.Player> players){return new LiveStateSnapshot(Protocol.VERSION,id,0,System.currentTimeMillis(),true,players,java.util.List.of(),java.util.List.of(),java.util.List.of(),java.util.List.of(),java.util.List.of(),null,java.util.List.of());}
}
