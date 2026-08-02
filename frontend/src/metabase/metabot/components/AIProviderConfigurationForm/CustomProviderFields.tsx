import { type FormikHelpers, useFormikContext } from "formik";
import { useMemo } from "react";
import { t } from "ttag";

import { useUpdateMetabotSettingsMutation } from "metabase/api";
import { useAdminSettings } from "metabase/api/utils";
import { SetByEnvVar } from "metabase/common/components/SetByEnvVar";
import { FormErrorMessage, FormProvider, FormTextInput } from "metabase/forms";

import { useAIProviderConfigurationContext } from "./AIProviderConfigurationContext";

const CUSTOM_SETTING_KEYS = [
  "llm-custom-api-key",
  "llm-custom-api-base-url",
] as const;

type CustomCredentialValues = {
  apiKey: string;
  baseUrl: string;
  model: string;
};

export const CustomProviderFields = ({
  connectedModel,
  isCurrentConfigured,
  isEnvSetting,
}: {
  connectedModel: string | undefined;
  isCurrentConfigured: boolean;
  isEnvSetting: boolean;
}) => {
  const [updateMetabotSettings] = useUpdateMetabotSettingsMutation();
  const { details } = useAdminSettings(CUSTOM_SETTING_KEYS);

  const initialValues = useMemo<CustomCredentialValues>(
    () => ({
      apiKey: String(details["llm-custom-api-key"]?.value ?? ""),
      baseUrl: String(details["llm-custom-api-base-url"]?.value ?? ""),
      model: (isCurrentConfigured ? connectedModel : undefined) ?? "",
    }),
    [connectedModel, details, isCurrentConfigured],
  );

  const handleSubmit = async (
    values: CustomCredentialValues,
    { resetForm }: FormikHelpers<CustomCredentialValues>,
  ) => {
    const changedValueOrNull = (field: "apiKey" | "baseUrl") =>
      values[field] !== initialValues[field] ? values[field] || null : null;

    await updateMetabotSettings({
      provider: "custom",
      model: values.model,
      credentials: {
        "api-key": changedValueOrNull("apiKey"),
        "base-url": changedValueOrNull("baseUrl"),
      },
    }).unwrap();

    resetForm({ values });
  };

  return (
    <FormProvider
      initialValues={initialValues}
      onSubmit={handleSubmit}
      enableReinitialize
    >
      <CustomCredentialFields
        isCurrentConfigured={isCurrentConfigured}
        isEnvSetting={isEnvSetting}
      />
    </FormProvider>
  );
};

const CustomCredentialFields = ({
  isCurrentConfigured,
  isEnvSetting,
}: {
  isCurrentConfigured: boolean;
  isEnvSetting: boolean;
}) => {
  const { dirty, submitForm, values } =
    useFormikContext<CustomCredentialValues>();

  const { details } = useAdminSettings(CUSTOM_SETTING_KEYS);
  const apiKeySetting = details["llm-custom-api-key"];
  const baseUrlSetting = details["llm-custom-api-base-url"];

  const apiKeyEnvName = apiKeySetting?.is_env_setting
    ? apiKeySetting.env_name
    : undefined;
  const baseUrlEnvName = baseUrlSetting?.is_env_setting
    ? baseUrlSetting.env_name
    : undefined;

  const isComplete =
    !!values.apiKey.trim() && !!values.baseUrl.trim() && !!values.model.trim();
  const connectHandler =
    isComplete && (!isCurrentConfigured || dirty) ? submitForm : null;
  const { isMutating } = useAIProviderConfigurationContext(connectHandler);

  return (
    <>
      <FormTextInput
        name="baseUrl"
        label={t`Base URL`}
        description={t`The Chat Completions base URL of your endpoint, including any version segment. We append /chat/completions to it.`}
        placeholder="https://api.example.com/v1"
        disabled={isMutating || isEnvSetting || !!baseUrlEnvName}
        w="100%"
      />
      {baseUrlEnvName && <SetByEnvVar varName={baseUrlEnvName} />}

      <FormTextInput
        name="apiKey"
        label={t`API key`}
        type="password"
        description={t`Sent to your endpoint as a bearer token.`}
        placeholder={t`Enter your API key`}
        disabled={isMutating || isEnvSetting || !!apiKeyEnvName}
        w="100%"
      />
      {apiKeyEnvName && <SetByEnvVar varName={apiKeyEnvName} />}

      <FormTextInput
        name="model"
        label={t`Model`}
        description={t`The model id to send, exactly as your endpoint expects it.`}
        placeholder="deepseek-chat"
        disabled={isMutating || isEnvSetting}
        w="100%"
      />

      <FormErrorMessage />
    </>
  );
};
