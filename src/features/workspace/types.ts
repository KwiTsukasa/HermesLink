export type Dict = Record<string, unknown>;

export type Field = {
  name: string;
  type: string;
  required: boolean;
  nullable?: boolean;
  default?: unknown;
  description?: string;
};

export type Entry = {
  id: string;
  label: string;
  group: string;
  labelKey?: string;
  groupKey?: string;
  groupId?: string;
  kind: string;
  method: string;
  path: string;
  description?: string;
  unavailable?: string;
  params: {
    path?: Field[];
    query?: Field[];
    body?: Field[];
    headers?: Field[];
    contentType?: string | null;
  };
  constraints: {
    mutating: boolean;
    confirmationRequired: boolean;
    notes?: string[];
    access: string;
  };
  response: {format: string};
};

export type OperationInput = {
  path?: Dict;
  query?: Dict;
  body?: Dict;
  attachment?: string;
};

export type Attachment = {id: string; name: string};
export type OperationResult =
  | {kind: 'value'; value: unknown}
  | {kind: 'file'; handle: string; name: string};

export type RouteIndex = ReadonlyMap<string, Entry>;

export type WorkspaceTab = 'overview' | 'skills' | 'jobs' | 'catalog';

export type DetailTarget =
  | {kind: 'usage'; profile?: string}
  | {kind: 'skill'; name: string; profile?: string}
  | {kind: 'job'; id: string; profile?: string}
  | {kind: 'models'; profile?: string};

export type DetailScreenProps = {
  byRoute: RouteIndex;
  onBack: () => void;
  onWrite: () => void;
  onOpenForm: (form: BusinessForm) => void;
};

export type BusinessField = {
  key: string;
  label: string;
  initial?: string;
  placeholder?: string;
  required?: boolean;
  multiline?: boolean;
  secret?: boolean;
  choices?: readonly {value: string; label: string}[];
};

export type BusinessForm = {
  title: string;
  action: string;
  detail?: string;
  fields: BusinessField[];
  build: (values: Readonly<Record<string, string>>) => OperationInput;
  entry: Entry;
  attachment?: Attachment;
  pickFile?: boolean;
  beforeSubmit?: (input: OperationInput) => Promise<void>;
  onSuccess?: (value: unknown, input: OperationInput) => void;
};
