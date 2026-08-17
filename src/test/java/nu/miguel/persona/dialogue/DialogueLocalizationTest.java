package nu.miguel.persona.dialogue;

import nu.miguel.persona.content.Content.Say;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DialogueLocalizationTest {
    private final Say say=new Say(null,"dialogue.guide.hello",Map.of("da-dk","Hej","en","Hello","default","Welcome"),List.of(),Duration.ZERO);
    @Test void selectsExactLanguageThenDefaultThenKey(){
        assertEquals("Hej",DialogueService.localized(say,Locale.forLanguageTag("da-DK")));
        assertEquals("Hello",DialogueService.localized(say,Locale.forLanguageTag("en-GB")));
        assertEquals("Welcome",DialogueService.localized(say,Locale.forLanguageTag("fr-FR")));
        Say keyOnly=new Say(null,"dialogue.guide.missing",Map.of(),List.of(),Duration.ZERO);
        assertEquals("dialogue.guide.missing",DialogueService.localized(keyOnly,Locale.ENGLISH));
    }
}
