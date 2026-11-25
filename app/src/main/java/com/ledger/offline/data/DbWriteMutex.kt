package com.ledger.offline.data

import kotlinx.coroutines.sync.Mutex

/**
 * 数据层进程内写互斥。
 *
 * 账户 / 分类的“先查询再插入”（ensureAccount、ensureBankAccount、defaultFor 兜底）
 * 与首次启动的种子数据写入都在同一把锁里串行执行，
 * 避免通知监听、短信广播与 Application 播种并发时插入重复的“微信 / 支付宝 / 银行卡”。
 *
 * 1.2.1 起账户余额的“读 -> 加/减 -> 写回”（adjustBalance，基金申购 / 赎回）也走这把锁，
 * 避免并发调整互相覆盖。
 *
 * 只有主进程使用 Room（没有多进程组件），进程内互斥足够；Room 自身保证单条 SQL 的原子性。
 */
internal object DbWriteMutex {
    val mutex: Mutex = Mutex()
}
