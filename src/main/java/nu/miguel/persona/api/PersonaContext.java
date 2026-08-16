package nu.miguel.persona.api;

import net.citizensnpcs.api.npc.NPC;
import nu.miguel.persona.content.Content;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.Optional;
import java.util.logging.Logger;

/** Immutable context supplied to extension callbacks. Values absent at a call site are empty. */
public record PersonaContext(Player player, Optional<NPC> npc, Optional<Content.Npc> npcDefinition,
                             Optional<Content.Dialogue> dialogue, Optional<Content.Quest> quest, Optional<Content.Phase> phase,
                             Optional<Content.Objective> objective, long current, long required,
                             PersonaApi api, Logger logger, File dataDirectory, ExpansionServices services) {
    public PersonaContext {
        npc = npc == null ? Optional.empty() : npc;
        npcDefinition = npcDefinition == null ? Optional.empty() : npcDefinition;
        dialogue = dialogue == null ? Optional.empty() : dialogue;
        quest = quest == null ? Optional.empty() : quest;
        phase = phase == null ? Optional.empty() : phase;
        objective = objective == null ? Optional.empty() : objective;
    }
}
