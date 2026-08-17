package nu.miguel.persona.state;

import nu.miguel.persona.content.Durations;

import java.time.Duration;
import java.time.Instant;

/** Human-friendly parsing used by memory commands and migration files. */
public final class MemoryTimes {
    private MemoryTimes(){}
    public static Instant parse(String raw){
        String value=raw.trim();if(value.equalsIgnoreCase("now"))return Instant.now();
        try{return Instant.parse(value);}catch(RuntimeException ignored){}
        if(value.startsWith("+")||value.matches("[0-9].*"))return Instant.now().plus(Durations.parse(value.startsWith("+")?value.substring(1):value));
        throw new IllegalArgumentException("expected now, ISO-8601, or a duration from now");
    }
    public static Duration ttl(String raw){Duration value=Duration.between(Instant.now(),parse(raw));return value.isNegative()?Duration.ZERO:value;}
}
