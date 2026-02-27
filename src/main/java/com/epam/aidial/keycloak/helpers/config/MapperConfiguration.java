package com.epam.aidial.keycloak.helpers.config;

import lombok.Value;
import org.keycloak.models.IdentityProviderMapperModel;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.provider.ProviderConfigProperty;

import java.util.List;

/**
 * Configuration for user-attributes mappers.
 *
 * <p>Backed by mapper model config properties and shared between IdP mappers and protocol mappers.
 */
@Value
public class MapperConfiguration {

    boolean fetchJobTitle;
    boolean fetchPhoto;

    private static final String FETCH_JOB_TITLE = "fetch.job.title";
    private static final String FETCH_PHOTO = "fetch.photo";

    /**
     * Builds configuration from an identity provider mapper model.
     *
     * @param model identity provider mapper model
     * @return configuration
     */
    public static MapperConfiguration fromModel(IdentityProviderMapperModel model) {
        return new MapperConfiguration(
            Boolean.parseBoolean(model.getConfig().getOrDefault(FETCH_JOB_TITLE, "true")),
            Boolean.parseBoolean(model.getConfig().getOrDefault(FETCH_PHOTO, "true"))
        );
    }

    /**
     * Builds configuration from a protocol mapper model.
     *
     * @param model protocol mapper model
     * @return configuration
     */
    public static MapperConfiguration fromModel(ProtocolMapperModel model) {
        return new MapperConfiguration(
            Boolean.parseBoolean(model.getConfig().getOrDefault(FETCH_JOB_TITLE, "true")),
            Boolean.parseBoolean(model.getConfig().getOrDefault(FETCH_PHOTO, "true"))
        );
    }

    private static final List<ProviderConfigProperty> CONFIG_PROPERTIES = List.of(
            getFetchJobTitleProperty(),
            getFetchPhotoProperty()
    );

    /**
     * Returns config properties supported by this mapper family.
     *
     * @return list of config properties
     */
    public static List<ProviderConfigProperty> getConfigProperties() {
        return CONFIG_PROPERTIES;
    }

    private static ProviderConfigProperty getFetchJobTitleProperty() {
        ProviderConfigProperty fetchJobTitle = new ProviderConfigProperty();
        fetchJobTitle.setName(FETCH_JOB_TITLE);
        fetchJobTitle.setLabel("Fetch Job Title");
        fetchJobTitle.setHelpText("Fetch job title from IdP and add to UserInfo as 'job_title' claim");
        fetchJobTitle.setType(ProviderConfigProperty.BOOLEAN_TYPE);
        fetchJobTitle.setDefaultValue("true");
        return fetchJobTitle;
    }

    private static ProviderConfigProperty getFetchPhotoProperty() {
        ProviderConfigProperty fetchPhoto = new ProviderConfigProperty();
        fetchPhoto.setName(FETCH_PHOTO);
        fetchPhoto.setLabel("Fetch Photo");
        fetchPhoto.setHelpText("Fetch photo from IdP and add to UserInfo as 'picture' claim (base64 encoded)");
        fetchPhoto.setType(ProviderConfigProperty.BOOLEAN_TYPE);
        fetchPhoto.setDefaultValue("true");
        return fetchPhoto;
    }
}
