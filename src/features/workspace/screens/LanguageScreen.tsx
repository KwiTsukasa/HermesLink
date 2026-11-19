import {useState} from 'react';

import {locales, useI18n, type Locale} from '../../../shared/i18n';
import {Feedback} from '../../../shared/ui/Feedback';
import {ListRow} from '../../../shared/ui/ListRow';
import {BusinessPage} from '../components/BusinessPage';
import {SearchField} from '../components/SearchField';

export function LanguageScreen({onBack}: {onBack: () => void}) {
  const {locale, mode, setLocale, t} = useI18n();
  const [search, setSearch] = useState('');
  const [saving, setSaving] = useState(false);
  const [failed, setFailed] = useState(false);
  const query = search.trim().toLowerCase();
  const visible = locales.filter((item) =>
    `${item.endonym} ${item.id}`.toLowerCase().includes(query),
  );

  async function choose(value: Locale | null) {
    if (saving) return;
    setSaving(true);
    setFailed(false);
    try {
      await setLocale(value);
    } catch {
      setFailed(true);
    } finally {
      setSaving(false);
    }
  }

  return (
    <BusinessPage title={t('language.label')} onBack={onBack}>
      <SearchField
        placeholder={t('language.searchPlaceholder')}
        value={search}
        onChange={setSearch}
        clearable
      />
      {saving ? <Feedback tone="loading" message={t('language.saving')} /> : null}
      {failed ? <Feedback tone="error" message={t('language.saveError')} /> : null}
      {!query ? (
        <ListRow
          title={t('mobile.language.useSystemLanguage')}
          subtitle={
            mode === 'system'
              ? locales.find((item) => item.id === locale)?.endonym
              : undefined
          }
          onPress={saving || mode === 'system' ? undefined : () => choose(null)}
        />
      ) : null}
      {visible.map((item) => {
        const selected = mode === 'pinned' && item.id === locale;
        return (
          <ListRow
            key={item.id}
            title={item.endonym}
            subtitle={selected ? t('mobile.language.selected') : undefined}
            onPress={saving || selected ? undefined : () => choose(item.id)}
          />
        );
      })}
      {query && !visible.length ? (
        <Feedback message={t('language.noResults')} />
      ) : null}
    </BusinessPage>
  );
}
