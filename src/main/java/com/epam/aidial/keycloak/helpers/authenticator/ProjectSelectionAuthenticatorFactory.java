package com.epam.aidial.keycloak.helpers.authenticator;

import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.authentication.ConfigurableAuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;

import com.epam.aidial.keycloak.helpers.config.ProjectEntitlementConfiguration;

import java.util.List;

/**
 * Factory for {@link ProjectSelectionAuthenticator} — the provider id the flow
 * execution references ("project-selection-authenticator").
 */
public class ProjectSelectionAuthenticatorFactory implements AuthenticatorFactory {

    public static final String PROVIDER_ID = "project-selection-authenticator";

    @Override
    public Authenticator create(KeycloakSession session) {
        return new ProjectSelectionAuthenticator();
    }

    @Override
    public void init(Config.Scope config) {
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
    }

    @Override
    public void close() {
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayType() {
        return "D-019 Project Selection Capture";
    }

    @Override
    public String getReferenceCategory() {
        return null;
    }

    @Override
    public boolean isConfigurable() {
        return true;
    }

    @Override
    public boolean isUserSetupAllowed() {
        return false;
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return ProjectEntitlementConfiguration.getConfigProperties();
    }

    @Override
    public AuthenticationExecutionModel.Requirement[] getRequirementChoices() {
        return ConfigurableAuthenticatorFactory.REQUIREMENT_CHOICES;
    }

    @Override
    public String getHelpText() {
        return "Copies the client's 'project' authorize parameter into the client session for the "
                + "project-selection protocol mapper to validate and emit (D-019)";
    }
}
