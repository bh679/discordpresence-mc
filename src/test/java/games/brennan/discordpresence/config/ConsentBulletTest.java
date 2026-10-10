package games.brennan.discordpresence.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsentBulletTest {

    @Test
    void plainConstructorsStayPlainLines() {
        assertFalse(new ConsentBullet("a", true).isToggle());
        assertFalse(new ConsentBullet("a", false, "tip").isToggle());
        assertNull(new ConsentBullet("a", true).onToggle());
    }

    @Test
    void toggleFactoryMakesASwitchThatReportsEachFlip() {
        List<Boolean> seen = new ArrayList<>();
        ConsentBullet b = ConsentBullet.toggle("Livestreaming", true, "tip", seen::add);
        assertTrue(b.isToggle());
        assertTrue(b.on());
        b.onToggle().accept(false);
        b.onToggle().accept(true);
        assertEquals(List.of(false, true), seen);
    }

    @Test
    void toggleFactoryRefusesANullListener() {
        assertThrows(NullPointerException.class, () -> ConsentBullet.toggle("x", true, null, null));
    }
}
