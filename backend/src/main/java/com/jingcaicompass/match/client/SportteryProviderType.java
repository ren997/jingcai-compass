package com.jingcaicompass.match.client;

public enum SportteryProviderType {
    /** NovaAPI：赛程、赔率、赛果共用同一个 matchId 的单源数据源。 */
    NOVA,
    /** 本地 fixture，不访问外部供应商。 */
    STUB
}
