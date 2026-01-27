package com.epam.aidial.keycloak.helpers.provider;

import com.epam.aidial.keycloak.helpers.model.IdpType;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class UserAttributesProviderFactoryTest {

    private final UserAttributesProviderFactory factory = new UserAttributesProviderFactory();

    @Test
    public void getProviderReturnsNullWhenIdpTypeIsNull() {
        UserAttributesProvider provider = factory.getProvider(null);

        assertNull(provider);
    }

    @Test
    public void getProviderReturnsMicrosoftProviderForMicrosoftIdpType() {
        UserAttributesProvider provider = factory.getProvider(IdpType.MICROSOFT);

        assertNotNull(provider);
        assertTrue(provider instanceof MsGraphUserAttributesProvider);
    }

    @Test
    public void getProviderReturnsNullForUnsupportedIdpType() {
        UserAttributesProvider provider = factory.getProvider(IdpType.GOOGLE);

        assertNull(provider);
    }
}

