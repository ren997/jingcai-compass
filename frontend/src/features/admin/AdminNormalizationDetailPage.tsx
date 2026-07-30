import { Alert, Button, Input, Modal, Radio } from 'antd';
import { useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import type { NormalizationEntityType, ProviderNormalizationEntity } from '../../services/admin';
import { formatTimestamp } from '../matches/matchPresentation';
import {
  useProviderNormalizationActions,
  useProviderNormalizationCandidatesQuery,
  useProviderNormalizationDetailQuery,
} from './useAdminQueries';

type Props = { entityType: NormalizationEntityType };
type Action = 'confirm' | 'reject' | 'reopen' | null;

function typeLabel(type: NormalizationEntityType) {
  return type === 'LEAGUE' ? '联赛' : '球队';
}

function displayEntity(entity: ProviderNormalizationEntity) {
  return entity.nameZh || entity.nameEn || `内部实体 #${entity.entityId}`;
}

/** 一条供应商联赛或球队映射的独立复核页。 */
export default function AdminNormalizationDetailPage({ entityType }: Props) {
  const { mappingId: rawId } = useParams();
  const [searchParams] = useSearchParams();
  const mappingId = rawId && /^\d+$/.test(rawId) && Number(rawId) > 0 ? Number(rawId) : undefined;
  const detailQuery = useProviderNormalizationDetailQuery(entityType, mappingId);
  const [keyword, setKeyword] = useState('');
  const candidatesQuery = useProviderNormalizationCandidatesQuery(entityType, mappingId, keyword);
  const actions = useProviderNormalizationActions();
  const [selectedEntityId, setSelectedEntityId] = useState<number>();
  const [action, setAction] = useState<Action>(null);
  const [confirmation, setConfirmation] = useState('');
  const [reason, setReason] = useState('');
  const listPath = entityType === 'LEAGUE' ? '/admin/normalizations/leagues' : '/admin/normalizations/teams';
  const backTo = `${listPath}${searchParams.toString() ? `?${searchParams}` : ''}`;

  if (mappingId === undefined) return <main className="admin-page"><section className="admin-state-card error"><h1>404</h1><p>标准化映射 ID 无效。</p><Link to={backTo}>返回复核列表</Link></section></main>;
  if (detailQuery.isPending) return <main className="admin-page"><section className="admin-state-card">正在读取复核详情……</section></main>;
  if (detailQuery.isError) return <main className="admin-page"><section className="admin-state-card error"><h1>复核详情不可用</h1><p>{detailQuery.error.message}</p><Link to={backTo}>返回复核列表</Link></section></main>;
  const detail = detailQuery.data!;
  const currentMappingId = mappingId;
  const mutation = action === 'confirm' ? actions.confirm : action === 'reject' ? actions.reject : actions.reopen;
  const requiredWord = action === 'confirm' ? '确认标准化' : action === 'reject' ? '确认拒绝' : '确认重新打开';
  const canConfirm = detail.mappingStatus === 'PENDING' && selectedEntityId !== undefined;

  async function submitAction() {
    if (!action || confirmation !== requiredWord) return;
    if (action === 'confirm' && selectedEntityId !== undefined) await actions.confirm.mutateAsync({ entityType, mappingId: currentMappingId, targetEntityId: selectedEntityId });
    if (action === 'reject') await actions.reject.mutateAsync({ entityType, mappingId: currentMappingId, reason });
    if (action === 'reopen') await actions.reopen.mutateAsync({ entityType, mappingId: currentMappingId });
    setAction(null); setConfirmation(''); setReason('');
  }

  return <main className="admin-page admin-workspace">
    <section className="admin-page-heading"><div><p className="eyebrow">Operations · Provider normalization</p><h1>{typeLabel(entityType)}标准化详情</h1>
      <p>只确认这一条 Provider 身份与内部标准实体的关系；不会确认赛事、写入全局别名或推断其他队伍。</p></div><Button loading={detailQuery.isFetching} onClick={() => void detailQuery.refetch()}>刷新</Button></section>
    {(actions.confirm.error || actions.reject.error || actions.reopen.error) && <Alert type="error" showIcon message={`操作未完成：${(actions.confirm.error || actions.reject.error || actions.reopen.error)?.message}`} />}
    <section className="admin-panel"><header className="admin-panel-heading"><div><h2>{detail.externalDisplayName || detail.externalId}</h2><span>{detail.providerCode} · {detail.mappingStatus}</span></div></header>
      <dl className="admin-metadata"><div><dt>外部 ID</dt><dd>{detail.externalId}</dd></div><div><dt>作用域</dt><dd>{detail.externalScope || '无'}</dd></div><div><dt>规范化键</dt><dd>{detail.externalNormalizedKey || '历史记录未采集'}</dd></div><div><dt>当前暂存实体</dt><dd>{detail.currentEntity ? displayEntity(detail.currentEntity) : '无'}</dd></div></dl>
      <p className="admin-metadata-note">映射方法：{detail.mappingMethod || '—'} · 置信度：{detail.mappingConfidence ?? '—'} · 更新：{formatTimestamp(detail.updatedAt)}</p>
    </section>
    {detail.mappingStatus === 'PENDING' && <section className="admin-panel"><header className="admin-panel-heading"><div><h2>选择内部{typeLabel(entityType)}实体</h2><span>必须明确选择，不接受赛事 ID</span></div></header>
      <label className="admin-modal-field">按中文或英文名称搜索<Input value={keyword} onChange={(event) => { setKeyword(event.target.value); setSelectedEntityId(undefined); }} placeholder="输入名称后刷新候选" /></label>
      {candidatesQuery.isError && <Alert type="error" showIcon message={`候选不可用：${candidatesQuery.error.message}`} />}
      {candidatesQuery.isPending ? <p className="admin-empty">正在搜索内部标准实体……</p> : <Radio.Group className="mapping-candidate-list" value={selectedEntityId} onChange={(event) => setSelectedEntityId(event.target.value)}>
        {(candidatesQuery.data ?? []).filter((item) => item.entityId !== detail.currentEntity?.entityId).map((item) => <Radio key={item.entityId} value={item.entityId}><div className="mapping-candidate-content"><strong>{displayEntity(item)}</strong><span>内部 ID：{item.entityId} {item.nameEn && item.nameZh ? `· ${item.nameEn}` : ''}</span></div></Radio>)}
      </Radio.Group>}
      {!candidatesQuery.isPending && (candidatesQuery.data ?? []).filter((item) => item.entityId !== detail.currentEntity?.entityId).length === 0 && <p className="admin-empty">没有可选内部实体。请保持待复核，等待体彩侧建立对应标准实体。</p>}
      <div className="admin-actions"><Button type="primary" disabled={!canConfirm} onClick={() => { setAction('confirm'); setConfirmation(''); }}>确认标准化</Button><Button danger onClick={() => { setAction('reject'); setConfirmation(''); }}>拒绝</Button></div>
    </section>}
    {detail.mappingStatus === 'REJECTED' && <section className="admin-panel"><p className="admin-empty">该映射已拒绝，不会用于后续自动候选。</p><Button onClick={() => { setAction('reopen'); setConfirmation(''); }}>重新打开</Button></section>}
    <section className="admin-panel"><header className="admin-panel-heading"><div><h2>审计历史</h2><span>只追加</span></div></header>
      {detail.auditHistory.length === 0 ? <p className="admin-empty">尚无人工操作。</p> : <ul className="admin-error-list">{detail.auditHistory.map((item, index) => <li key={`${item.createdAt}-${index}`}><strong>{item.actionType}</strong><span>{item.operatorId} · {formatTimestamp(item.createdAt)}</span></li>)}</ul>}
    </section>
    <Link className="admin-back-link" to={backTo}>返回{typeLabel(entityType)}复核列表</Link>
    <Modal title={action === 'confirm' ? `确认${typeLabel(entityType)}标准化` : action === 'reject' ? `拒绝${typeLabel(entityType)}映射` : `重新打开${typeLabel(entityType)}映射`} open={action !== null} onCancel={() => setAction(null)} onOk={() => void submitAction()} confirmLoading={mutation.isPending} okText={requiredWord} okButtonProps={{ disabled: confirmation !== requiredWord }}>
      {action === 'confirm' && <p>将 Provider “{detail.externalDisplayName || detail.externalId}”确认映射为所选内部实体。此操作不会确认任何赛事或写入全局别名。</p>}
      {action === 'reject' && <label className="admin-modal-field">可选原因<Input value={reason} onChange={(event) => setReason(event.target.value)} /></label>}
      <label className="admin-modal-field">输入“{requiredWord}”以进行二次确认<Input value={confirmation} onChange={(event) => setConfirmation(event.target.value)} /></label>
    </Modal>
  </main>;
}
