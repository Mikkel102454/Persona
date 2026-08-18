package nu.miguel.persona.editor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import nu.miguel.persona.Main;
import nu.miguel.persona.api.EditorCatalogDescriptor;
import nu.miguel.persona.api.EditorSchemaDescriptor;
import nu.miguel.persona.api.PersonaApi;
import nu.miguel.persona.api.NpcMemoryService;
import nu.miguel.persona.citizens.PersonaTrait;
import nu.miguel.persona.content.ContentFormat;
import nu.miguel.persona.editor.protocol.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.scheduler.BukkitTask;
import java.util.function.Function;

public final class EditorClient implements AutoCloseable {
    private final Main plugin;
    // Shaded Paper plugins cannot reliably use ServiceLoader module discovery because
    // the server and plugin class loaders have different service visibility.
    private final ObjectMapper json = jsonMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final EditorIdentity identity;
    private final URI service;
    private final boolean allowInsecure;
    private final Map<UUID, LiveSession> sessions = new ConcurrentHashMap<>();

    static ObjectMapper jsonMapper() { return new ObjectMapper().registerModule(new JavaTimeModule()); }
    static URI reconnectUri(URI socketUri, long afterSequence) {
        if (afterSequence < 0) throw new IllegalArgumentException("afterSequence must not be negative");
        return URI.create(socketUri + (socketUri.getQuery() == null ? "?" : "&") + "after=" + afterSequence);
    }

    public EditorClient(Main plugin) throws Exception {
        this.plugin = plugin;
        this.identity = EditorIdentity.loadOrCreate(plugin.getDataFolder().toPath().resolve("editor-identity.properties"));
        this.service = URI.create(plugin.getConfig().getString("editor.hosted-url", "https://editor.persona.invalid"));
        this.allowInsecure = plugin.getConfig().getBoolean("editor.allow-insecure-transport", false);
        requireSecure(service, "hosted URL");
    }

    public CompletableFuture<SessionCreateResponse> open(CommandSender sender, EditorScope scope) {
        return open(sender, scope, SessionRestrictions.UNRESTRICTED);
    }

    public CompletableFuture<SessionCreateResponse> open(CommandSender sender, EditorScope scope,
                                                          SessionRestrictions restrictions) {
        try {
            MetadataDocuments metadata = captureMetadata();
            long issuedAt = System.currentTimeMillis();
            String nonce = randomNonce();
            String initiatorId = sender instanceof Player player ? player.getUniqueId().toString() : "console";
            Set<Capability> allowedCapabilities = requestedCapabilities(sender);
            if (!plugin.getConfig().getBoolean("editor.publish-enabled", false)) {
                EnumSet<Capability> configured = EnumSet.copyOf(allowedCapabilities); configured.remove(Capability.CONTENT_PUBLISH);
                allowedCapabilities = Set.copyOf(configured);
            }
            final Set<Capability> capabilities = allowedCapabilities;
            SessionCreateRequest unsigned = new SessionCreateRequest(Protocol.VERSION, identity.installationId(),
                    identity.publicKey(), initiatorId, sender.getName(), scope, restrictions, capabilities, issuedAt, nonce, "");
            SessionCreateRequest request = new SessionCreateRequest(unsigned.protocolVersion(), unsigned.installationId(),
                    unsigned.installationPublicKey(), unsigned.initiatorId(), unsigned.initiatorName(), unsigned.scope(),
                    unsigned.restrictions(), unsigned.requestedCapabilities(), unsigned.issuedAt(), unsigned.nonce(), identity.sign(unsigned.signingInput()));
            InstallationChallengeRequest challengeRequest=new InstallationChallengeRequest(Protocol.VERSION,identity.installationId(),identity.publicKey());
            HttpRequest challengeHttp=HttpRequest.newBuilder(service.resolve("/api/v1/editor/sessions/installation-challenges"))
                    .timeout(Duration.ofSeconds(15)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(challengeRequest))).build();
            return http.sendAsync(challengeHttp,HttpResponse.BodyHandlers.ofString()).thenApply(response->decode(response,InstallationChallengeResponse.class))
                    .thenCompose(challenge->{InstallationChallengeProof unsignedProof=new InstallationChallengeProof(Protocol.VERSION,challenge.challengeId(),identity.installationId(),identity.publicKey(),challenge.challenge(),"");InstallationChallengeProof proof=new InstallationChallengeProof(unsignedProof.protocolVersion(),unsignedProof.challengeId(),unsignedProof.installationId(),unsignedProof.installationPublicKey(),unsignedProof.challenge(),identity.sign(unsignedProof.signingInput()));try{HttpRequest proofHttp=HttpRequest.newBuilder(service.resolve("/api/v1/editor/sessions/installation-challenges/prove")).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(proof))).build();return http.sendAsync(proofHttp,HttpResponse.BodyHandlers.ofString()).thenApply(response->decode(response,InstallationChallengeProofResponse.class));}catch(Exception error){return CompletableFuture.failedFuture(error);}})
                    .thenCompose(proof->{try{HttpRequest createHttp=HttpRequest.newBuilder(service.resolve("/api/v1/editor/sessions")).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json").header("Authorization","Bearer "+proof.installationLease()).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request))).build();return http.sendAsync(createHttp,HttpResponse.BodyHandlers.ofString()).thenApply(response->decode(response,SessionCreateResponse.class));}catch(Exception error){return CompletableFuture.failedFuture(error);}})
                    .thenCompose(response -> connect(response, scope, restrictions, initiatorId, sender.getName(), capabilities)
                            .thenCompose(ignored -> uploadMetadata(response, metadata))
                            .thenCompose(ignored -> uploadSnapshot(response, scope))
                            .thenApply(ignored -> response));
        } catch (Exception e) { return CompletableFuture.failedFuture(e); }
    }

    private CompletableFuture<WebSocket> connect(SessionCreateResponse response, EditorScope scope,
                                                  SessionRestrictions restrictions,
                                                  String initiatorId, String initiatorName,
                                                  Set<Capability> requestedCapabilities) {
        URI socketUri = URI.create(response.pluginSocketUrl());
        requireSecure(socketUri, "plugin WebSocket URL");
        LiveSession live = new LiveSession(response, scope, restrictions, socketUri, initiatorId, initiatorName,
                requestedCapabilities);
        sessions.put(response.sessionId(), live);
        return live.connect().whenComplete((socket, error) -> {
            if (error != null) sessions.remove(response.sessionId(), live);
            else live.startHeartbeat();
        });
    }

    public static Set<Capability> requestedCapabilities(CommandSender sender) {
        EnumSet<Capability> capabilities = EnumSet.of(Capability.CONTENT_VIEW);
        if (sender.hasPermission("persona.admin.editor.players")) capabilities.add(Capability.PLAYER_VIEW);
        if (sender.hasPermission("persona.admin.editor.memories")) capabilities.add(Capability.MEMORY_VIEW);
        if (sender.hasPermission("persona.admin.editor.drafts")) capabilities.add(Capability.DRAFT_EDIT);
        if (sender.hasPermission("persona.admin.editor.publish")) capabilities.add(Capability.CONTENT_PUBLISH);
        if (sender.hasPermission("persona.admin.editor.mutate")) capabilities.add(Capability.LIVE_MUTATE);
        return Set.copyOf(capabilities);
    }

    public Collection<LocalSession> sessions() {
        return sessions.values().stream().map(LiveSession::local).sorted(Comparator.comparing(LocalSession::expiresAt)).toList();
    }

    public boolean ownedBy(String reference, CommandSender sender) {
        LiveSession live = resolve(reference);
        return sender instanceof Player player ? live.initiatorId.equals(player.getUniqueId().toString())
                : live.initiatorId.equals("console");
    }

    public CompletableFuture<EditorSessionStatus> status(String reference) {
        LiveSession live = resolve(reference);
        return request(live, "GET", "/status", null, EditorSessionStatus.class)
                .thenApply(status -> { live.updateCapabilities(status.grantedCapabilities()); return status; });
    }

    public CompletableFuture<EditorSessionStatus> trust(String reference, Set<Capability> approved) {
        LiveSession live = resolve(reference);
        CapabilityGrantRequest body = new CapabilityGrantRequest(Protocol.VERSION,
                approved.stream().filter(live.requestedCapabilities::contains)
                        .filter(capability -> capability != Capability.CONTENT_VIEW)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()));
        return request(live, "PUT", "/capabilities", body, EditorSessionStatus.class)
                .thenApply(status -> { live.updateCapabilities(status.grantedCapabilities()); return status; });
    }

    public CompletableFuture<EditorSessionStatus> revokeTrust(String reference) {
        LiveSession live = resolve(reference);
        return request(live, "DELETE", "/capabilities", null, EditorSessionStatus.class)
                .thenApply(status -> { live.updateCapabilities(status.grantedCapabilities()); return status; });
    }

    public CompletableFuture<PublishStatusResponse> publishStatus(String reference, UUID publishId) {
        return request(resolve(reference), "GET", "/publishes/" + publishId, null, PublishStatusResponse.class);
    }

    public CompletableFuture<PublishStatusResponse> rollback(String reference, UUID publishId) {
        LiveSession live = resolve(reference);
        return request(live, "GET", "/publishes/" + publishId + "/rollback-project", null, RollbackProject.class)
                .thenCompose(project -> {
                    CompletableFuture<RollbackApplyResult> applied = new CompletableFuture<>();
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        try { applied.complete(plugin.rollbackEditorProject(project)); }
                        catch (RuntimeException error) { applied.complete(new RollbackApplyResult(Protocol.VERSION,
                                project.rollbackId(), project.publishId(), false, project.currentRevision(), null,
                                Objects.toString(error.getMessage(), error.getClass().getSimpleName()))); }
                    });
                    return applied.thenCompose(result -> request(live, "POST", "/publishes/" + publishId
                            + "/rollback-result", result, PublishStatusResponse.class));
                });
    }

    public void close(String reference) { resolve(reference).close(); }

    public void playerQuit(UUID playerId) {
        if (!plugin.getConfig().getBoolean("editor.close-on-player-quit", true)) return;
        sessions.values().stream().filter(session -> session.initiatorId.equals(playerId.toString()))
                .toList().forEach(LiveSession::close);
    }

    /** Called on the server thread so Bukkit permission state is never queried asynchronously. */
    public void revokeLostPermissions() {
        sessions.values().stream().filter(session -> !session.initiatorId.equals("console")).toList().forEach(session -> {
            try {
                UUID id = UUID.fromString(session.initiatorId);
                Player player = plugin.getServer().getPlayer(id);
                if (player != null && (!player.hasPermission("persona.admin.editor.open")
                        || !player.hasPermission("persona.admin.editor.view")
                        || !requestedCapabilities(player).containsAll(session.requestedCapabilities))) session.close();
            } catch (IllegalArgumentException ignored) { session.close(); }
        });
    }

    private <T> CompletableFuture<T> request(LiveSession live, String method, String suffix,
                                              Object body, Class<T> responseType) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(service.resolve("/api/v1/editor/sessions/"
                            + live.id + suffix)).timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + live.response.pluginLeaseToken());
            if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
            else builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            return http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> decode(response, responseType));
        } catch (Exception error) { return CompletableFuture.failedFuture(error); }
    }

    private LiveSession resolve(String reference) {
        if (reference == null || reference.isBlank()) throw new IllegalArgumentException("Missing editor session ID");
        List<LiveSession> matches = sessions.values().stream()
                .filter(session -> session.id.toString().startsWith(reference.toLowerCase(Locale.ROOT))).toList();
        if (matches.isEmpty()) throw new IllegalArgumentException("Unknown editor session " + reference);
        if (matches.size() > 1) throw new IllegalArgumentException("Ambiguous editor session prefix " + reference);
        return matches.getFirst();
    }

    private CompletableFuture<String> uploadSnapshot(SessionCreateResponse response, EditorScope scope) {
        try {
            ContentSnapshotBuilder.Project project = ContentSnapshotBuilder.read(plugin.getDataFolder().toPath(), scope);
            ContentSnapshot unsigned = new ContentSnapshot(Protocol.VERSION, response.sessionId(), project.revision(),
                    ContentFormat.CURRENT, Instant.now(), identity.publicKey(), project.files(),project.folders(),project.manifestDigest(), "");
            ContentSnapshot snapshot = new ContentSnapshot(unsigned.protocolVersion(), unsigned.sessionId(),
                    unsigned.revision(), unsigned.contentFormatVersion(), unsigned.createdAt(),
                    unsigned.installationPublicKey(), unsigned.files(),unsigned.folders(),unsigned.manifestDigest(), identity.sign(unsigned.signingInput()));
            HttpRequest request = HttpRequest.newBuilder(service.resolve("/api/v1/editor/sessions/"
                            + response.sessionId() + "/snapshot"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + response.pluginLeaseToken())
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(snapshot))).build();
            return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(result -> {
                if (result.statusCode() < 200 || result.statusCode() >= 300)
                    throw new CompletionException(new IOException("Snapshot upload returned HTTP "
                            + result.statusCode() + ": " + result.body()));
                return project.revision();
            });
        } catch (Exception e) { return CompletableFuture.failedFuture(e); }
    }

    private MetadataDocuments captureMetadata() throws Exception {
        List<EditorSchemaDocument> schemas=new ArrayList<>();
        for(EditorSchemaDescriptor descriptor:plugin.api().editorSchemas()){
            String source=json.writeValueAsString(descriptor.schema());schemas.add(new EditorSchemaDocument(descriptor.contentType(),descriptor.typeId(),
                    descriptor.extensionId(),descriptor.extensionVersion(),source,sha256(source)));
        }
        List<EditorCatalogDocument> catalogs=new ArrayList<>();
        for(EditorCatalogDescriptor descriptor:plugin.api().editorCatalogs()){
            var metadata=descriptor.metadata();String source=json.writeValueAsString(metadata.valueSchema());
            catalogs.add(new EditorCatalogDocument(descriptor.catalogId(),descriptor.extensionId(),descriptor.extensionVersion(),metadata.revision(),source,
                    sha256(source),metadata.permission(),metadata.cachePolicy().name(),metadata.dependencyFields().stream().sorted().toList(),metadata.missingValuePolicy().name()));
        }
        return new MetadataDocuments(List.copyOf(schemas),List.copyOf(catalogs));
    }

    private CompletableFuture<String> uploadMetadata(SessionCreateResponse response,MetadataDocuments documents){
        try{
            EditorMetadataSnapshot draft=new EditorMetadataSnapshot(Protocol.VERSION,response.sessionId(),Instant.now(),identity.publicKey(),"",
                    documents.schemas(),documents.catalogs(),"");
            String revision=metadataRevision(draft.manifest());
            EditorMetadataSnapshot unsigned=new EditorMetadataSnapshot(draft.protocolVersion(),draft.sessionId(),draft.createdAt(),draft.installationPublicKey(),revision,
                    draft.schemas(),draft.catalogs(),"");
            EditorMetadataSnapshot snapshot=new EditorMetadataSnapshot(unsigned.protocolVersion(),unsigned.sessionId(),unsigned.createdAt(),unsigned.installationPublicKey(),
                    unsigned.revision(),unsigned.schemas(),unsigned.catalogs(),identity.sign(unsigned.signingInput()));
            HttpRequest request=HttpRequest.newBuilder(service.resolve("/api/v1/editor/sessions/"+response.sessionId()+"/metadata"))
                    .timeout(Duration.ofSeconds(20)).header("Authorization","Bearer "+response.pluginLeaseToken()).header("Content-Type","application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(snapshot))).build();
            return http.sendAsync(request,HttpResponse.BodyHandlers.ofString()).thenApply(result->{if(result.statusCode()<200||result.statusCode()>=300)
                throw new CompletionException(new IOException("Metadata upload returned HTTP "+result.statusCode()+": "+result.body()));return revision;});
        }catch(Exception error){return CompletableFuture.failedFuture(error);}
    }

    private static String metadataRevision(List<String> manifest) throws Exception {MessageDigest digest=MessageDigest.getInstance("SHA-256");
        for(String line:manifest){digest.update(line.getBytes(StandardCharsets.UTF_8));digest.update((byte)'\n');}return HexFormat.of().formatHex(digest.digest());}
    private static String sha256(String source) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));}
    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        return Objects.toString(cause.getMessage(), cause.getClass().getSimpleName());
    }

    public boolean matchesConfiguration(String hostedUrl, boolean insecureTransport) {
        try { return service.equals(URI.create(hostedUrl)) && allowInsecure == insecureTransport; }
        catch (IllegalArgumentException e) { return false; }
    }

    /** Refreshes signed hosted snapshots after Persona has atomically activated new content. */
    public void contentChanged() {
        try { MetadataDocuments metadata=captureMetadata();sessions.values().forEach(session->session.publishSnapshot(metadata)); }
        catch(Exception error){plugin.getLogger().warning("Editor metadata refresh failed: "+error.getMessage());}
    }

    private <T> T decode(HttpResponse<String> response, Class<T> type) {
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new CompletionException(new IOException("Editor service returned HTTP " + response.statusCode() + ": " + response.body()));
        try { return json.readValue(response.body(), type); }
        catch (IOException e) { throw new CompletionException(e); }
    }

    private String randomNonce() { byte[] value = new byte[16]; new java.security.SecureRandom().nextBytes(value); return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    private void requireSecure(URI uri, String label) {
        boolean secure = Set.of("https", "wss").contains(uri.getScheme());
        boolean local = Set.of("localhost", "127.0.0.1", "::1").contains(uri.getHost());
        if (!secure && !allowInsecure && !local) throw new IllegalArgumentException(label + " must use TLS");
    }

    @Override public void close() {
        sessions.values().forEach(LiveSession::close);
        sessions.clear();
    }

    static LiveStateSnapshot delta(LiveStateSnapshot previous,LiveStateSnapshot current,long revision){
        List<String> removed=new ArrayList<>();
        List<LiveStateSnapshot.Player> players=changed(previous.players(),current.players(),value->"player:"+value.playerId(),removed);
        List<LiveStateSnapshot.Npc> npcs=changed(previous.npcs(),current.npcs(),value->"npc:"+value.definitionId()+":"+value.instanceId()+":"+Objects.toString(value.playerId(),"shared"),removed);
        List<LiveStateSnapshot.Behavior> behaviors=changed(previous.behaviors(),current.behaviors(),value->"behavior:"+value.definitionId()+":"+value.instanceId()+":"+Objects.toString(value.playerId(),"shared")+":"+value.behaviorId(),removed);
        List<LiveStateSnapshot.Quest> quests=changed(previous.quests(),current.quests(),value->"quest:"+value.playerId()+":"+value.questId(),removed);
        List<LiveStateSnapshot.Dialogue> dialogues=changed(previous.dialogues(),current.dialogues(),value->"dialogue:"+value.playerId(),removed);
        List<LiveStateSnapshot.Memory> memories=changed(previous.memories(),current.memories(),value->"memory:"+Objects.toString(value.playerId(),"global")+":"+value.npcDefinition()+":"+value.npcInstance()+":"+value.key(),removed);
        long lastTrace=previous.traces().stream().mapToLong(LiveStateSnapshot.GraphTrace::sequence).max().orElse(0);
        List<LiveStateSnapshot.GraphTrace> traces=current.traces().stream().filter(value->value.sequence()>lastTrace).toList();
        boolean serverChanged=!Objects.equals(previous.server(),current.server());
        if(players.isEmpty()&&npcs.isEmpty()&&behaviors.isEmpty()&&quests.isEmpty()&&dialogues.isEmpty()&&memories.isEmpty()&&traces.isEmpty()&&removed.isEmpty()&&!serverChanged)return null;
        return new LiveStateSnapshot(Protocol.VERSION,current.subscriptionId(),revision,current.capturedAt(),false,players,npcs,behaviors,quests,dialogues,memories,traces,serverChanged?current.server():null,removed);
    }
    private static <T> List<T> changed(List<T> before,List<T> after,Function<T,String> key,List<String> removed){Map<String,T> old=new LinkedHashMap<>();before.forEach(value->old.put(key.apply(value),value));List<T> result=new ArrayList<>();Set<String> present=new HashSet<>();for(T value:after){String id=key.apply(value);present.add(id);if(!Objects.equals(old.get(id),value))result.add(value);}old.keySet().stream().filter(id->!present.contains(id)).forEach(removed::add);return List.copyOf(result);}
    private static LiveStateSnapshot version(LiveStateSnapshot value,long revision,boolean full,List<String> removed){return new LiveStateSnapshot(Protocol.VERSION,value.subscriptionId(),revision,value.capturedAt(),full,value.players(),value.npcs(),value.behaviors(),value.quests(),value.dialogues(),value.memories(),value.traces(),value.server(),removed);}

    private final class LiveSession implements WebSocket.Listener {
        private final SessionCreateResponse response;
        private final EditorScope scope;
        private final SessionRestrictions restrictions;
        private final URI socketUri;
        private final UUID id;
        private final long expiresAt;
        private final String initiatorId;
        private final String initiatorName;
        private final Set<Capability> requestedCapabilities;
        private final AtomicLong sequence = new AtomicLong();
        private final AtomicLong receivedSequence = new AtomicLong();
        private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
        private final AtomicBoolean publishClaimInFlight = new AtomicBoolean();
        private final Map<UUID,LiveFeed> liveFeeds=new ConcurrentHashMap<>();
        private final StringBuilder fragments = new StringBuilder();
        private volatile WebSocket socket;
        private volatile boolean closed;
        private volatile boolean everConnected;
        private volatile int reconnectAttempt;
        private volatile Set<Capability> grantedCapabilities = Set.of(Capability.CONTENT_VIEW);
        private volatile BukkitTask publishPollTask;

        private LiveSession(SessionCreateResponse response, EditorScope scope, SessionRestrictions restrictions, URI socketUri,
                            String initiatorId, String initiatorName, Set<Capability> requestedCapabilities) {
            this.response = response; this.scope = scope; this.restrictions = restrictions; this.socketUri = socketUri;
            this.id = response.sessionId(); this.expiresAt = response.expiresAt().toEpochMilli();
            this.initiatorId = initiatorId; this.initiatorName = initiatorName;
            this.requestedCapabilities = Set.copyOf(requestedCapabilities);
        }
        private LocalSession local() {
            return new LocalSession(id, initiatorId, initiatorName, scope, restrictions, requestedCapabilities,
                    response.verificationCode(), response.expiresAt());
        }
        private CompletableFuture<WebSocket> connect() {
            URI reconnectUri = reconnectUri(socketUri, receivedSequence.get());
            return http.newWebSocketBuilder().header("Authorization", "Bearer " + response.pluginLeaseToken())
                    .connectTimeout(Duration.ofSeconds(10)).buildAsync(reconnectUri, this)
                    .thenApply(connected -> {
                        socket = connected; reconnectAttempt = 0; everConnected = true;
                        reconnectScheduled.set(false); return connected;
                    });
        }
        private void startHeartbeat() {
            plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, this::heartbeat, 20L * 20L);
        }
        private synchronized void updateCapabilities(Set<Capability> capabilities) {
            grantedCapabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
            if (grantedCapabilities.contains(Capability.CONTENT_PUBLISH)) startPublishPolling();
            else stopPublishPolling();
        }
        private synchronized void startPublishPolling() {
            if (closed || publishPollTask != null) return;
            publishPollTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
                    plugin, this::pollPublication, 1L, 40L);
        }
        private synchronized void stopPublishPolling() {
            if (publishPollTask != null) publishPollTask.cancel();
            publishPollTask = null;
        }
        private void pollPublication() {
            if (closed || !grantedCapabilities.contains(Capability.CONTENT_PUBLISH)
                    || !publishClaimInFlight.compareAndSet(false, true)) return;
            claimPublication().thenCompose(project -> project == null
                    ? CompletableFuture.completedFuture(null) : applyPublication(project))
                    .whenComplete((ignored, error) -> {
                        publishClaimInFlight.set(false);
                        if (error != null && !closed)
                            plugin.getLogger().warning("Editor publication failed: " + rootMessage(error));
                    });
        }
        private CompletableFuture<PublishProject> claimPublication() {
            HttpRequest request = HttpRequest.newBuilder(service.resolve("/api/v1/editor/sessions/" + id + "/publishes/claim"))
                    .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + response.pluginLeaseToken())
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(reply -> reply.statusCode() == 204
                            ? null : decode(reply, PublishProject.class));
        }
        private CompletableFuture<PublishStatusResponse> applyPublication(PublishProject project) {
            CompletableFuture<PublishApplyResult> applied = new CompletableFuture<>();
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                try { applied.complete(plugin.publishEditorProject(project)); }
                catch (RuntimeException error) { applied.complete(new PublishApplyResult(Protocol.VERSION,
                        project.publishId(), false, project.baseRevision(), null,
                        Objects.toString(error.getMessage(), error.getClass().getSimpleName()))); }
            });
            return applied.thenCompose(result -> request(this, "POST", "/publishes/" + project.publishId()
                    + "/result", result, PublishStatusResponse.class));
        }
        private void heartbeat() {
            if (closed || System.currentTimeMillis() >= expiresAt) return;
            try {
                WebSocket current = socket;
                if (current != null && !current.isOutputClosed()) send(current, Protocol.HEARTBEAT,
                        Map.of("at", System.currentTimeMillis()));
            } catch (Exception e) { plugin.getLogger().warning("Editor heartbeat failed: " + e.getMessage()); }
            startHeartbeat();
        }
        private void send(WebSocket target, String type, Map<String, Object> payload) throws Exception {
            long next = sequence.incrementAndGet();
            byte[] serialized = json.writeValueAsBytes(payload);
            String digest = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(serialized));
            String input = Protocol.VERSION + "\n" + id + "\n" + next + "\n" + type + "\n" + digest;
            target.sendText(json.writeValueAsString(new SocketMessage(Protocol.VERSION, id, next,
                    type, payload, identity.sign(input))), true);
        }
        @Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            synchronized (fragments) {
                fragments.append(data);
                if (last) { handleIncoming(fragments.toString()); fragments.setLength(0); }
            }
            webSocket.request(1); return null;
        }
        private void handleIncoming(String text) {
            try {
                var tree = json.readTree(text);
                if (tree.has("controlType")) {
                    if (tree.path("protocolVersion").asInt() != Protocol.VERSION
                            || !id.toString().equals(tree.path("sessionId").asText())) return;
                    if (Protocol.RESYNC_REQUIRED.equals(tree.path("controlType").asText())) {
                        receivedSequence.set(tree.path("latestSequence").asLong());
                        uploadSnapshot(response, scope).exceptionally(error -> {
                            plugin.getLogger().warning("Editor full resynchronization failed: " + error.getMessage()); return null;
                        });
                    }
                    return;
                }
                SocketMessage message = json.treeToValue(tree, SocketMessage.class);
                if (message.protocolVersion() != Protocol.VERSION || !id.equals(message.sessionId())
                        || !Set.of(Protocol.HEARTBEAT, Protocol.RESYNC_REQUEST, Protocol.VALIDATION_REQUEST,Protocol.CATALOG_REQUEST,
                        Protocol.LIVE_SUBSCRIBE,Protocol.LIVE_UNSUBSCRIBE,Protocol.BEHAVIOR_MUTATION_REQUEST,
                        Protocol.MEMORY_MUTATION_REQUEST).contains(message.type())) return;
                receivedSequence.accumulateAndGet(message.sequence(), Math::max);
                if (Protocol.VALIDATION_REQUEST.equals(message.type())) {
                    ValidationRequest validationRequest = json.convertValue(message.payload(), ValidationRequest.class);
                    if (validationRequest.protocolVersion() != Protocol.VERSION || validationRequest.requestId() == null
                            || validationRequest.draftId() == null) return;
                    validate(validationRequest);
                }
                if(Protocol.CATALOG_REQUEST.equals(message.type())){
                    EditorCatalogRequest catalogRequest=json.convertValue(message.payload(),EditorCatalogRequest.class);
                    if(catalogRequest.protocolVersion()!=Protocol.VERSION||catalogRequest.requestId()==null||catalogRequest.catalogId()==null)return;
                    queryCatalog(catalogRequest);
                }
                if(Protocol.LIVE_SUBSCRIBE.equals(message.type()))subscribeLive(json.convertValue(message.payload(),LiveSubscribeRequest.class));
                if(Protocol.LIVE_UNSUBSCRIBE.equals(message.type()))unsubscribeLive(json.convertValue(message.payload(),LiveUnsubscribeRequest.class));
                if(Protocol.BEHAVIOR_MUTATION_REQUEST.equals(message.type()))mutateBehavior(json.convertValue(message.payload(),BehaviorMutationRequest.class));
                if(Protocol.MEMORY_MUTATION_REQUEST.equals(message.type()))mutateMemory(json.convertValue(message.payload(),MemoryMutationRequest.class));
            } catch (Exception e) { plugin.getLogger().warning("Ignored invalid editor relay message: " + e.getMessage()); }
        }
        private void mutateBehavior(BehaviorMutationRequest request){plugin.getServer().getScheduler().runTask(plugin,()->{LiveMutationResult result;try{validateMutation(request.requestId(),request.protocolVersion(),request.npcDefinition(),request.npcInstance(),request.playerId());NPC npc=findNpc(request.npcDefinition(),request.npcInstance());Player player=request.playerId()==null?null:requirePlayer(request.playerId());int count;if(request.operation()==BehaviorMutationRequest.Operation.SIGNAL){String signal=Objects.toString(request.signal(),"").toLowerCase(Locale.ROOT);if(!signal.matches("[a-z0-9][a-z0-9_.:-]{0,63}")||request.data().size()>16||request.data().entrySet().stream().anyMatch(entry->entry.getKey().length()>64||entry.getValue().length()>256))throw new IllegalArgumentException("Invalid bounded signal");count=plugin.behaviors().signalSelected(npc,player,signal,Map.copyOf(request.data()));}else count=plugin.behaviors().controlSelected(npc,player,request.operation().name().toLowerCase(Locale.ROOT));if(count==0)throw new IllegalArgumentException("No matching behavior runtime");result=mutationResult(request.requestId(),"behavior",request.operation().name(),true,"Applied to "+count+" runtime(s)",request.npcDefinition()+"/"+request.npcInstance(),null,null);}catch(RuntimeException error){result=mutationResult(request==null?null:request.requestId(),"behavior",request==null?"UNKNOWN":String.valueOf(request.operation()),false,Objects.toString(error.getMessage(),"Mutation rejected"),"",null,null);}sendMutationResult(result);});}
        private void mutateMemory(MemoryMutationRequest request){plugin.getServer().getScheduler().runTask(plugin,()->{LiveMutationResult result;NpcMemoryService.Value old=null,next=null;try{validateMutation(request.requestId(),request.protocolVersion(),request.npcDefinition(),request.npcInstance(),request.playerId());if(request.key()==null||!request.key().matches("[a-z0-9][a-z0-9_.:-]{0,127}"))throw new IllegalArgumentException("Invalid memory key");if(!restrictions.contentNamespaces().isEmpty()){String namespace=request.key().contains(":")?request.key().substring(0,request.key().indexOf(':')):request.npcDefinition().split(":",2)[0];if(!restrictions.contentNamespaces().contains(namespace))throw new SecurityException("Memory namespace exceeds session scope");}old=plugin.memories().get(request.playerId(),request.npcDefinition(),request.npcInstance(),request.key()).orElse(null);if(request.expectedUpdatedAt()!=null&&(old==null||old.updatedAt().toEpochMilli()!=request.expectedUpdatedAt()))throw new IllegalStateException("Memory changed since confirmation; refresh and confirm again");String source="editor:"+id;Duration ttl=request.expiresAt()==null?null:Duration.ofMillis(Math.max(1,request.expiresAt()-System.currentTimeMillis()));switch(request.operation()){case SET->{NpcMemoryService.Type type=NpcMemoryService.Type.valueOf(Objects.toString(request.valueType(),"").toUpperCase(Locale.ROOT));if(request.value()==null||request.value().length()>4096)throw new IllegalArgumentException("Invalid memory value");next=plugin.memories().set(request.playerId(),request.npcDefinition(),request.npcInstance(),request.key(),type,request.value(),ttl,source);}case INCREMENT->{if(request.amount()==null||!Double.isFinite(request.amount()))throw new IllegalArgumentException("Invalid increment");next=plugin.memories().adjust(request.playerId(),request.npcDefinition(),request.npcInstance(),request.key(),request.amount(),ttl,source);}case EXPIRE->{if(request.expiresAt()==null)throw new IllegalArgumentException("Expiry is required");plugin.memories().expire(request.playerId(),request.npcDefinition(),request.npcInstance(),request.key(),Instant.ofEpochMilli(request.expiresAt()),source);next=plugin.memories().get(request.playerId(),request.npcDefinition(),request.npcInstance(),request.key()).orElse(null);}case DELETE->{plugin.memories().forget(request.playerId(),request.npcDefinition(),request.npcInstance(),request.key(),source);}}result=mutationResult(request.requestId(),"memory",request.operation().name(),true,"Memory mutation applied",request.npcDefinition()+"/"+request.npcInstance()+"/"+request.key(),old,next);}catch(RuntimeException error){result=mutationResult(request==null?null:request.requestId(),"memory",request==null?"UNKNOWN":String.valueOf(request.operation()),false,Objects.toString(error.getMessage(),"Mutation rejected"),"",old,next);}sendMutationResult(result);});}
        private void validateMutation(UUID requestId,int version,String definition,String instance,UUID playerId){if(version!=Protocol.VERSION||requestId==null||definition==null||!definition.matches("[a-z0-9][a-z0-9_.:-]{0,127}")||instance==null||instance.isBlank()||instance.length()>128)throw new IllegalArgumentException("Invalid mutation target");if(!plugin.getConfig().getBoolean("editor.live-mutations-enabled",false))throw new SecurityException("Live mutations are disabled on this server");if(!requestedCapabilities.contains(Capability.LIVE_MUTATE))throw new SecurityException("Live mutation capability was not granted");if(playerId!=null&&!restrictions.playerIds().isEmpty()&&!restrictions.playerIds().contains(playerId.toString()))throw new SecurityException("Player exceeds session scope");if(!restrictions.npcIds().isEmpty()&&!restrictions.npcIds().contains(definition)&&!restrictions.npcIds().contains(instance))throw new SecurityException("NPC exceeds session scope");}
        private NPC findNpc(String definition,String instance){for(NPC npc:CitizensAPI.getNPCRegistry()){PersonaTrait trait=npc.getTraitNullable(PersonaTrait.class);if(trait!=null&&trait.bound()&&!trait.projection()&&definition.equals(trait.definitionId())&&instance.equals(Objects.toString(trait.instanceId(),npc.getUniqueId().toString())))return npc;}throw new IllegalArgumentException("NPC instance is unavailable");}
        private Player requirePlayer(UUID id){Player player=plugin.getServer().getPlayer(id);if(player==null)throw new IllegalArgumentException("Player is not online");return player;}
        private LiveMutationResult mutationResult(UUID requestId,String type,String operation,boolean success,String message,String target,NpcMemoryService.Value old,NpcMemoryService.Value next){return new LiveMutationResult(Protocol.VERSION,requestId,type,operation,success,message,target,memoryState(old),memoryState(next),System.currentTimeMillis());}
        private LiveMutationResult.MemoryState memoryState(NpcMemoryService.Value value){return value==null?new LiveMutationResult.MemoryState(false,null,null,0,0,null,null):new LiveMutationResult.MemoryState(true,value.type().name(),String.valueOf(value.value()),value.createdAt().toEpochMilli(),value.updatedAt().toEpochMilli(),value.expiresAt()==null?null:value.expiresAt().toEpochMilli(),value.source());}
        private void sendMutationResult(LiveMutationResult result){try{WebSocket current=socket;if(current!=null&&!current.isOutputClosed())send(current,Protocol.LIVE_MUTATION_RESULT,json.convertValue(result,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));}catch(Exception error){plugin.getLogger().warning("Could not send live mutation result: "+error.getMessage());}}
        private void subscribeLive(LiveSubscribeRequest request){plugin.getServer().getScheduler().runTask(plugin,()->{
            try{validateLiveRequest(request);LiveFeed feed=new LiveFeed(request);LiveFeed old=liveFeeds.put(request.subscriptionId(),feed);if(old!=null)old.close();
                sendLiveAck(request.subscriptionId(),true,request.refreshMillis(),"");feed.start();}
            catch(RuntimeException error){sendLiveAck(request==null?null:request.subscriptionId(),false,0,Objects.toString(error.getMessage(),"Subscription rejected"));}
        });}
        private void unsubscribeLive(LiveUnsubscribeRequest request){plugin.getServer().getScheduler().runTask(plugin,()->{if(request==null||request.protocolVersion()!=Protocol.VERSION)return;LiveFeed feed=liveFeeds.remove(request.subscriptionId());if(feed!=null)feed.close();sendLiveAck(request.subscriptionId(),false,0,"unsubscribed");});}
        private void validateLiveRequest(LiveSubscribeRequest request){if(request==null||request.protocolVersion()!=Protocol.VERSION||request.subscriptionId()==null||request.topics().isEmpty()||request.refreshMillis()<250||request.refreshMillis()>5000)throw new IllegalArgumentException("Invalid live subscription");
            if(!requestedCapabilities.contains(Capability.PLAYER_VIEW))throw new IllegalArgumentException("Live viewing was not granted");if(request.topics().contains(LiveTopic.MEMORIES)&&!requestedCapabilities.contains(Capability.MEMORY_VIEW))throw new IllegalArgumentException("Memory viewing was not granted");
            LiveFilter filter=request.filter();if(!restrictions.playerIds().isEmpty()&&!restrictions.playerIds().containsAll(filter.playerIds().stream().map(UUID::toString).toList()))throw new IllegalArgumentException("Player filter exceeds session scope");if(!restrictions.worlds().isEmpty()&&!restrictions.worlds().containsAll(filter.worlds()))throw new IllegalArgumentException("World filter exceeds session scope");Set<String> npcs=new HashSet<>(filter.npcDefinitions());npcs.addAll(filter.npcInstances());if(!restrictions.npcIds().isEmpty()&&!restrictions.npcIds().containsAll(npcs))throw new IllegalArgumentException("NPC filter exceeds session scope");}
        private void sendLiveAck(UUID subscriptionId,boolean accepted,int refresh,String message){try{WebSocket current=socket;if(current!=null&&!current.isOutputClosed())send(current,Protocol.LIVE_SUBSCRIPTION_ACK,json.convertValue(new LiveSubscriptionAck(Protocol.VERSION,subscriptionId,accepted,refresh,message),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));}catch(Exception error){plugin.getLogger().warning("Could not send live subscription acknowledgement: "+error.getMessage());}}
        private void queryCatalog(EditorCatalogRequest request){
            plugin.getServer().getScheduler().runTask(plugin,()->{
                EditorCatalogResult result;
                try{
                    var descriptor=plugin.api().editorCatalogs().stream().filter(value->value.catalogId().equals(PersonaApi.canonical(request.catalogId()))).findFirst()
                            .orElseThrow(()->new IllegalArgumentException("Catalog is not installed"));
                    if(!descriptor.metadata().revision().equals(request.expectedRevision()))result=catalogResult(request,descriptor.metadata().revision(),EditorCatalogResult.Status.STALE,List.of(),false,"Catalog revision changed; refresh metadata");
                    else if(!catalogPermission(descriptor.metadata().permission()))result=catalogResult(request,descriptor.metadata().revision(),EditorCatalogResult.Status.DENIED,List.of(),false,"Catalog permission denied");
                    else{
                        var page=plugin.api().queryEditorCatalog(request.catalogId(),new nu.miguel.persona.api.EditorCatalogProvider.CatalogQuery(request.search(),request.page(),request.pageSize(),request.dependencies()));
                        List<EditorCatalogResult.Value> values=page.values().stream().map(value->new EditorCatalogResult.Value(value.id(),value.label(),value.description(),value.group(),value.icon(),value.deprecated())).toList();
                        result=catalogResult(request,page.revision(),EditorCatalogResult.Status.LIVE,values,page.hasMore(),"");
                    }
                }catch(IllegalArgumentException error){result=catalogResult(request,request.expectedRevision(),EditorCatalogResult.Status.UNAVAILABLE,List.of(),false,Objects.toString(error.getMessage(),"Catalog unavailable"));}
                catch(RuntimeException error){result=catalogResult(request,request.expectedRevision(),EditorCatalogResult.Status.ERROR,List.of(),false,"Catalog provider failed safely");plugin.getLogger().warning("Editor catalog "+request.catalogId()+" failed: "+error.getMessage());}
                try{WebSocket current=socket;if(current!=null&&!current.isOutputClosed())send(current,Protocol.CATALOG_RESULT,json.convertValue(result,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));}
                catch(Exception error){plugin.getLogger().warning("Could not send editor catalog result: "+error.getMessage());}
            });
        }
        private boolean catalogPermission(String permission){if(permission==null||permission.isBlank()||initiatorId.equals("console"))return true;
            try{Player player=plugin.getServer().getPlayer(UUID.fromString(initiatorId));return player!=null&&player.hasPermission(permission);}catch(IllegalArgumentException error){return false;}}
        private EditorCatalogResult catalogResult(EditorCatalogRequest request,String revision,EditorCatalogResult.Status status,List<EditorCatalogResult.Value> values,boolean more,String message){
            return new EditorCatalogResult(Protocol.VERSION,request.requestId(),request.catalogId(),revision,status,values,request.page(),more,message);}
        private void validate(ValidationRequest validationRequest) {
            request(this, "GET", "/validation/" + validationRequest.requestId() + "/project", null,
                    ValidationProject.class).thenAccept(project -> {
                if (!id.equals(project.sessionId()) || !validationRequest.requestId().equals(project.requestId())
                        || !validationRequest.draftId().equals(project.draftId())) return;
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    try {
                        ValidationResult result;
                        try { result = plugin.validateEditorProject(project); }
                        catch (RuntimeException validationError) {
                            result = new ValidationResult(Protocol.VERSION, project.requestId(), project.draftId(), false, project.proposedRevision(),
                                    ContentFormat.CURRENT, List.of(new ValidationDiagnostic("config.yml", 1, 1, null, null, null,
                                    Objects.toString(validationError.getMessage(), validationError.getClass().getSimpleName()),
                                    null, "ERROR")));
                        }
                        WebSocket current = socket;
                        if (current != null && !current.isOutputClosed())
                            send(current, Protocol.VALIDATION_RESULT, json.convertValue(result,
                                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
                    } catch (Exception error) {
                        plugin.getLogger().warning("Could not send editor validation result: " + error.getMessage());
                    }
                });
            }).exceptionally(error -> {
                plugin.getLogger().warning("Editor candidate validation request failed: " + error.getMessage()); return null;
            });
        }
        private void publishSnapshot(MetadataDocuments metadata) {
            uploadMetadata(response,metadata).thenCompose(ignored->uploadSnapshot(response, scope)).thenAccept(revision -> {
                try {
                    WebSocket current = socket;
                    if (current != null && !current.isOutputClosed())
                        send(current, Protocol.SNAPSHOT_CHANGED, Map.of("revision", revision));
                } catch (Exception e) { plugin.getLogger().warning("Editor snapshot notification failed: " + e.getMessage()); }
            }).exceptionally(error -> {
                plugin.getLogger().warning("Editor snapshot refresh failed: " + error.getMessage()); return null;
            });
        }
        @Override public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            socket = null; reconnect(); return null;
        }
        @Override public void onError(WebSocket webSocket, Throwable error) {
            socket = null;
            if (!closed) plugin.getLogger().warning("Editor connection interrupted: " + error.getMessage());
            reconnect();
        }
        private void reconnect() {
            if (!everConnected || closed || System.currentTimeMillis() >= expiresAt) { sessions.remove(id, this); return; }
            if (!reconnectScheduled.compareAndSet(false, true)) return;
            long delay = Math.min(20L * 30L, 20L << Math.min(reconnectAttempt++, 5));
            plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin,
                    () -> {
                        reconnectScheduled.set(false);
                        connect().exceptionally(error -> { reconnect(); return null; });
                    }, delay);
        }
        private void close() {
            closed = true;
            stopPublishPolling();
            liveFeeds.values().forEach(LiveFeed::close);liveFeeds.clear();
            sessions.remove(id, this);
            HttpRequest revoke = HttpRequest.newBuilder(service.resolve("/api/v1/editor/sessions/" + id))
                    .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + response.pluginLeaseToken())
                    .DELETE().build();
            http.sendAsync(revoke, HttpResponse.BodyHandlers.discarding());
            WebSocket current = socket;
            if (current != null) current.sendClose(WebSocket.NORMAL_CLOSURE, "Plugin disabled");
        }

        private final class LiveFeed {
            private final LiveSubscribeRequest request;private final AtomicReference<LiveStateSnapshot> pending=new AtomicReference<>();private final AtomicBoolean draining=new AtomicBoolean();
            private BukkitTask task;private LiveStateSnapshot previous;private long revision;private volatile boolean stopped;
            LiveFeed(LiveSubscribeRequest request){this.request=request;}
            void start(){capture();long ticks=Math.max(5,(long)Math.ceil(request.refreshMillis()/50d));task=plugin.getServer().getScheduler().runTaskTimer(plugin,this::capture,ticks,ticks);}
            void capture(){if(stopped)return;LiveStateSnapshot value=LiveSnapshotBuilder.capture(plugin,request,restrictions,0,previous==null);pending.set(value);drain();}
            void drain(){if(!draining.compareAndSet(false,true))return;CompletableFuture.runAsync(()->{
                try{while(!stopped){LiveStateSnapshot latest=pending.getAndSet(null);if(latest==null)break;LiveStateSnapshot outgoing=previous==null?version(latest,++revision,true,List.of()):delta(previous,latest,revision+1);if(outgoing==null)continue;revision++;WebSocket current=socket;if(current==null||current.isOutputClosed()){pending.set(latest);break;}send(current,outgoing.full()?Protocol.LIVE_SNAPSHOT:Protocol.LIVE_DELTA,json.convertValue(outgoing,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));previous=latest;}}
                catch(Exception error){plugin.getLogger().warning("Live editor snapshot failed: "+error.getMessage());}finally{draining.set(false);if(pending.get()!=null&&!stopped)drain();}});}
            void close(){stopped=true;if(task!=null)task.cancel();pending.set(null);}
        }
    }

    public record LocalSession(UUID id, String initiatorId, String initiatorName, EditorScope scope,
                               SessionRestrictions restrictions, Set<Capability> requestedCapabilities,
                               String verificationCode, Instant expiresAt) {}
    private record MetadataDocuments(List<EditorSchemaDocument> schemas,List<EditorCatalogDocument> catalogs){}
}
