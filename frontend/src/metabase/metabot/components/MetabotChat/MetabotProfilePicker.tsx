import { useState } from "react";
import { t } from "ttag";

import { Button, Icon, Menu } from "metabase/ui";

import {
  type MetabotProfileId,
  PICKABLE_PROFILES,
  getMetabotProfileLabel,
  resolveMetabotProfileId,
} from "../../constants";

export const MetabotProfilePicker = ({
  profileId,
  onProfileSelect,
}: {
  profileId: MetabotProfileId | undefined;
  onProfileSelect: (profileId: MetabotProfileId) => void;
}) => {
  const [opened, setOpened] = useState(false);

  const activeProfileId = resolveMetabotProfileId(profileId);

  // Some surfaces pin their profile — the transforms page forces transforms_codegen
  // and the SQL/ask panels are seeded with their own. There `getProfile` discards
  // whatever the picker writes, so it would render as a control that ignores clicks.
  if (!PICKABLE_PROFILES.includes(activeProfileId)) {
    return null;
  }

  return (
    <Menu opened={opened} onChange={setOpened} position="bottom-end" shadow="md">
      <Menu.Target>
        <Button
          variant="subtle"
          size="xs"
          c="text-primary"
          px="sm"
          rightSection={<Icon name="chevrondown" size={10} />}
          data-testid="metabot-profile-picker"
        >
          {getMetabotProfileLabel(activeProfileId)}
        </Button>
      </Menu.Target>
      <Menu.Dropdown>
        <Menu.Label>{t`Assistant`}</Menu.Label>
        {PICKABLE_PROFILES.map((id) => (
          <Menu.Item
            key={id}
            aria-current={id === activeProfileId || undefined}
            onClick={() => onProfileSelect(id)}
          >
            {getMetabotProfileLabel(id)}
          </Menu.Item>
        ))}
      </Menu.Dropdown>
    </Menu>
  );
};
