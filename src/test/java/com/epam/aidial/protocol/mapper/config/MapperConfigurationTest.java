package com.epam.aidial.protocol.mapper.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.keycloak.models.IdentityProviderMapperModel;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.provider.ProviderConfigProperty;

public class MapperConfigurationTest {

    @Test
    public void fromIdentityProviderMapperModelUsesDefaultsWhenConfigMissing() {
        IdentityProviderMapperModel model = mock(IdentityProviderMapperModel.class);
        Map<String, String> config = new HashMap<>();
        when(model.getConfig()).thenReturn(config);

        MapperConfiguration mapperConfiguration = MapperConfiguration.fromModel(model);

        assertTrue(mapperConfiguration.isFetchJobTitle());
        assertTrue(mapperConfiguration.isFetchPhoto());
    }

    @Test
    public void fromIdentityProviderMapperModelReadsExplicitValues() {
        IdentityProviderMapperModel model = mock(IdentityProviderMapperModel.class);
        Map<String, String> config = new HashMap<>();
        config.put("fetch.job.title", "false");
        config.put("fetch.photo", "false");
        when(model.getConfig()).thenReturn(config);

        MapperConfiguration mapperConfiguration = MapperConfiguration.fromModel(model);

        assertFalse(mapperConfiguration.isFetchJobTitle());
        assertFalse(mapperConfiguration.isFetchPhoto());
    }

    @Test
    public void fromProtocolMapperModelUsesDefaultsWhenConfigMissing() {
        ProtocolMapperModel model = mock(ProtocolMapperModel.class);
        Map<String, String> config = new HashMap<>();
        when(model.getConfig()).thenReturn(config);

        MapperConfiguration mapperConfiguration = MapperConfiguration.fromModel(model);

        assertTrue(mapperConfiguration.isFetchJobTitle());
        assertTrue(mapperConfiguration.isFetchPhoto());
    }

    @Test
    public void fromProtocolMapperModelReadsExplicitValues() {
        ProtocolMapperModel model = mock(ProtocolMapperModel.class);
        Map<String, String> config = new HashMap<>();
        config.put("fetch.job.title", "false");
        config.put("fetch.photo", "false");
        when(model.getConfig()).thenReturn(config);

        MapperConfiguration mapperConfiguration = MapperConfiguration.fromModel(model);

        assertFalse(mapperConfiguration.isFetchJobTitle());
        assertFalse(mapperConfiguration.isFetchPhoto());
    }

    @Test
    public void getConfigPropertiesContainsExpectedEntries() {
        List<ProviderConfigProperty> properties = MapperConfiguration.getConfigProperties();

        assertEquals(2, properties.size());
        assertEquals("fetch.job.title", properties.get(0).getName());
        assertEquals("fetch.photo", properties.get(1).getName());
    }
}

