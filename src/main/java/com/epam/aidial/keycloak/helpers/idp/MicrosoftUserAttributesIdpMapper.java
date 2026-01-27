package com.epam.aidial.keycloak.helpers.idp;

import com.epam.aidial.keycloak.helpers.model.IdpType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Identity provider mapper that enriches Keycloak user attributes using Microsoft Graph.
 */
@Slf4j
@RequiredArgsConstructor
public class MicrosoftUserAttributesIdpMapper extends UserAttributesIdpMapper {

    public static final String PROVIDER_ID = "microsoft-entra-user-attributes-idp-mapper";

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String[] getCompatibleProviders() {
        return new String[] { "microsoft", "oidc" };
    }

    @Override
    public String getDisplayCategory() {
        return "External API Enrichment";
    }

    @Override
    public String getDisplayType() {
        return "Microsoft Entra ID User Attributes";
    }

    @Override
    public String getHelpText() {
        return "Fetches user profile and photo from Microsoft Graph API at login";
    }

    @Override
    IdpType getIdpType() {
        return IdpType.MICROSOFT;
    }
}
