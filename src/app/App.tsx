import {StrictMode} from 'react';

import {WorkspaceScreen} from '../features/workspace/screens/WorkspaceScreen';
import {LocaleProvider} from '../shared/i18n';
import {ThemeProvider} from '../shared/theme/ThemeProvider';

export default function App() {
  return (
    <StrictMode>
      <LocaleProvider>
        <ThemeProvider>
          <WorkspaceScreen />
        </ThemeProvider>
      </LocaleProvider>
    </StrictMode>
  );
}
