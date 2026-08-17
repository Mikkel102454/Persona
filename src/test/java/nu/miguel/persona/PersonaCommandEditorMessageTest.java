package nu.miguel.persona;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import nu.miguel.persona.editor.EditorClient;
import nu.miguel.persona.editor.protocol.Capability;
import nu.miguel.persona.editor.protocol.SessionCreateResponse;
import org.junit.jupiter.api.Test;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

final class PersonaCommandEditorMessageTest {
    @Test void editorCardIsConciseAndMakesItsThreeActionsClickable() {
        UUID id = UUID.fromString("12345678-1234-4234-8234-123456789abc");
        SessionCreateResponse session = new SessionCreateResponse(id,
                "http://localhost/editor/session/12345678", "ABCDEF234567",
                "ws://localhost/ws/v1/plugin?session=12345678", "secret-lease",
                Instant.parse("2026-08-18T04:00:00Z"));

        Component message = PersonaCommand.editorReadyMessage(session);
        String text = text(message);
        List<ClickEvent<?>> clicks = clicks(message);

        assertTrue(text.contains("Persona Editor"));
        assertTrue(text.contains("Open editor"));
        assertTrue(text.contains("Code: ABCDEF234567"));
        assertTrue(text.contains("Trust editor"));
        assertFalse(text.contains(id.toString()));
        assertFalse(text.contains("PlayerName"));
        assertFalse(text.contains("DRAFT_EDIT"));
        assertFalse(text.contains("2026-08-18"));
        assertTrue(clicks.stream().anyMatch(event -> event.action() == ClickEvent.Action.OPEN_URL
                && value(event).equals("http://localhost/editor/session/12345678")));
        assertTrue(clicks.stream().anyMatch(event -> event.action() == ClickEvent.Action.COPY_TO_CLIPBOARD
                && value(event).equals("ABCDEF234567")));
        assertTrue(clicks.stream().anyMatch(event -> event.action() == ClickEvent.Action.RUN_COMMAND
                && value(event).equals("/persona editor trust 12345678")));
    }

    @Test void firstTrustCommandGrantsWithoutAReviewOrConfirmStep() {
        Main plugin = mock(Main.class);
        EditorClient editor = mock(EditorClient.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        CommandSender sender = mock(CommandSender.class);
        when(plugin.editor()).thenReturn(editor);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(sender.hasPermission("persona.admin.editor.open")).thenReturn(true);
        when(sender.hasPermission("persona.admin.editor.view")).thenReturn(true);
        when(sender.hasPermission("persona.admin.editor.drafts")).thenReturn(true);
        when(editor.trust(eq("12345678"), anySet())).thenReturn(CompletableFuture.completedFuture(null));
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return mock(BukkitTask.class);
        }).when(scheduler).runTask(eq(plugin), any(Runnable.class));

        PersonaCommand command = new PersonaCommand(plugin);
        assertTrue(command.onCommand(sender, mock(Command.class), "persona",
                new String[]{"editor", "trust", "12345678"}));

        verify(editor).trust(eq("12345678"), argThat(capabilities ->
                capabilities.contains(Capability.CONTENT_VIEW) && capabilities.contains(Capability.DRAFT_EDIT)));
        verify(editor, never()).status(anyString());
        assertTrue(command.onTabComplete(sender, mock(Command.class), "persona",
                new String[]{"editor", "trust", "12345678", ""}).isEmpty());
    }

    private static String text(Component component) {
        StringBuilder value = new StringBuilder();
        collectText(component, value);
        return value.toString();
    }

    private static void collectText(Component component, StringBuilder value) {
        if (component instanceof TextComponent text) value.append(text.content());
        component.children().forEach(child -> collectText(child, value));
    }

    private static List<ClickEvent<?>> clicks(Component component) {
        List<ClickEvent<?>> value = new ArrayList<>();
        collectClicks(component, value);
        return value;
    }

    private static void collectClicks(Component component, List<ClickEvent<?>> value) {
        if (component.clickEvent() != null) value.add(component.clickEvent());
        component.children().forEach(child -> collectClicks(child, value));
    }

    private static String value(ClickEvent<?> event) {
        return assertInstanceOf(ClickEvent.Payload.Text.class, event.payload()).value();
    }
}
