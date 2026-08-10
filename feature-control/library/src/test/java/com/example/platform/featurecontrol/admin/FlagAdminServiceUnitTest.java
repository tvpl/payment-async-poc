package com.example.platform.featurecontrol.admin;

import com.example.platform.featurecontrol.model.FlagDefinition;
import com.example.platform.featurecontrol.model.FlagType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * FTR-04: "The admin operation SHALL usar autorização forte... auditoria com ator." The actor
 * validation short-circuits before any Redis call, so it's testable without a real store.
 */
class FlagAdminServiceUnitTest {

    private static final FlagDefinition DEF = new FlagDefinition(
            "f", FlagType.BOOLEAN, true, 0, null, null, null, "on", "off");

    private static FlagAdminService serviceWithNoStore() {
        // requireActor() must throw before ever touching `store`, so null is safe here.
        return new FlagAdminService(null, null, null, null);
    }

    @Test
    void putRejectsANullActor() {
        assertThrows(IllegalArgumentException.class, () -> serviceWithNoStore().put(DEF, null));
    }

    @Test
    void putRejectsABlankActor() {
        assertThrows(IllegalArgumentException.class, () -> serviceWithNoStore().put(DEF, "   "));
    }

    @Test
    void putRejectsAnEmptyActor() {
        assertThrows(IllegalArgumentException.class, () -> serviceWithNoStore().put(DEF, ""));
    }

    @Test
    void deleteRejectsANullActor() {
        assertThrows(IllegalArgumentException.class, () -> serviceWithNoStore().delete("f", 0L, null));
    }

    @Test
    void deleteRejectsABlankActor() {
        assertThrows(IllegalArgumentException.class, () -> serviceWithNoStore().delete("f", 0L, "  "));
    }
}
