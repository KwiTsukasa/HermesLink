import type {DetailScreenProps, DetailTarget, Dict} from '../types';
import {JobDetailsScreen} from './JobDetailsScreen';
import {ModelDetailsScreen} from './ModelDetailsScreen';
import {SkillDetailsScreen} from './SkillDetailsScreen';
import {UsageScreen} from './UsageScreen';

export function DetailScreen({
  target,
  onEdit,
  onAuthorize,
  ...props
}: DetailScreenProps & {
  target: DetailTarget;
  onEdit: (profile: string, job: Dict) => void;
  onAuthorize: () => void;
}) {
  switch (target.kind) {
    case 'usage':
      return (
        <UsageScreen
          entry={props.byRoute.get('GET /api/analytics/models')}
          profile={target.profile}
          onBack={props.onBack}
        />
      );
    case 'skill':
      return (
        <SkillDetailsScreen
          key={JSON.stringify([target.profile, target.name])}
          {...props}
          name={target.name}
          profile={target.profile}
        />
      );
    case 'job':
      return (
        <JobDetailsScreen
          key={JSON.stringify([target.profile, target.id])}
          {...props}
          id={target.id}
          profile={target.profile}
          onEdit={onEdit}
        />
      );
    case 'models':
      return (
        <ModelDetailsScreen
          key={target.profile}
          {...props}
          profile={target.profile}
          onAuthorize={onAuthorize}
        />
      );
  }
}
