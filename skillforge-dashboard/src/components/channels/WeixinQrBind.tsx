import React, { useEffect, useRef, useState } from 'react';
import { Button, Spin, Alert, QRCode, message } from 'antd';
import { useQueryClient } from '@tanstack/react-query';
import { weixinQrLoginStart, weixinQrLoginStatus } from '../../api/channels';

const POLL_INTERVAL_MS = 2000;
const QR_SIZE = 200;

/** Backend status strings that mean "stop polling, success". */
const CONFIRMED_STATUS = 'confirmed';
/** Backend status strings that mean "stop polling, terminal failure". */
const TERMINAL_FAIL_STATUSES = ['expired', 'error', 'cancel', 'canceled', 'cancelled'];

type BindPhase = 'idle' | 'loading' | 'polling' | 'bound' | 'failed';

interface WeixinQrBindProps {
  /** Whether the surrounding drawer is open — used to tear down polling on close. */
  active: boolean;
}

function extractErrorMessage(err: unknown, fallback: string): string {
  if (err && typeof err === 'object' && 'response' in err) {
    const e = (err as { response?: { data?: { error?: unknown } } }).response?.data?.error;
    if (typeof e === 'string') return e;
  }
  return fallback;
}

/**
 * WeChat (ClawBot) scan-to-bind panel. Shown in edit mode once a weixin
 * channel config already exists. Fetches a login QR URL, renders it as a
 * scannable QR code, then polls the status endpoint every ~2s until the scan is
 * confirmed (bound), the QR expires/errors, or the panel is torn down.
 *
 * Wire shape note: the backend's `qrcode_img_content` is a URL the user scans
 * (we encode it into a QR via AntD <QRCode>), NOT a base64 image. The 32-hex
 * `qrcode` is the polling ticket passed to the status endpoint.
 */
const WeixinQrBind: React.FC<WeixinQrBindProps> = ({ active }) => {
  const queryClient = useQueryClient();
  const [phase, setPhase] = useState<BindPhase>('idle');
  const [qrUrl, setQrUrl] = useState<string | null>(null);
  const [errorText, setErrorText] = useState<string | null>(null);

  // Refs so the polling effect / async callbacks never setState after unmount.
  const qrcodeRef = useRef<string | null>(null);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const mountedRef = useRef(true);

  function clearTimer() {
    if (timerRef.current !== null) {
      clearTimeout(timerRef.current);
      timerRef.current = null;
    }
  }

  // Master teardown: on unmount and whenever the drawer is closed.
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      clearTimer();
    };
  }, []);

  // React to drawer open/close. On open, re-arm mountedRef so a future
  // destroyOnClose remount can't leave it stuck false (which would silently
  // kill the scan panel). On close, stop polling and reset transient UI so
  // reopening starts clean (no stale QR, no leaked timer).
  useEffect(() => {
    if (active) {
      mountedRef.current = true;
    } else {
      clearTimer();
      qrcodeRef.current = null;
      setPhase('idle');
      setQrUrl(null);
      setErrorText(null);
    }
  }, [active]);

  /**
   * Arm the next poll tick. Like {@link poll}, only reads refs — never reads
   * state — because it runs from a stale closure captured at render time.
   */
  function scheduleNextPoll() {
    clearTimer();
    timerRef.current = setTimeout(poll, POLL_INTERVAL_MS);
  }

  /**
   * One poll tick. MUST only read refs (qrcodeRef / mountedRef / timerRef),
   * never component state — it is invoked from a setTimeout closure captured at
   * render time, so any state read here would be stale.
   */
  async function poll() {
    const qrcode = qrcodeRef.current;
    if (!qrcode || !mountedRef.current) return;
    try {
      const res = await weixinQrLoginStatus(qrcode);
      if (!mountedRef.current || qrcodeRef.current !== qrcode) return;
      const { status, bound } = res.data;
      if (bound || status === CONFIRMED_STATUS) {
        clearTimer();
        setPhase('bound');
        setQrUrl(null);
        message.success('微信绑定成功');
        queryClient.invalidateQueries({ queryKey: ['channel-configs'] });
        return;
      }
      if (TERMINAL_FAIL_STATUSES.includes(status)) {
        clearTimer();
        setPhase('failed');
        setQrUrl(null);
        setErrorText(`二维码已失效（${status}），请重新获取`);
        return;
      }
      // pending / not-yet-confirmed → keep polling.
      scheduleNextPoll();
    } catch (err) {
      if (!mountedRef.current || qrcodeRef.current !== qrcode) return;
      clearTimer();
      setPhase('failed');
      setQrUrl(null);
      const msg = extractErrorMessage(err, '轮询绑定状态失败，请重新获取二维码');
      setErrorText(msg);
      message.error(msg);
    }
  }

  async function handleStart() {
    clearTimer();
    setErrorText(null);
    setQrUrl(null);
    setPhase('loading');
    try {
      const res = await weixinQrLoginStart();
      if (!mountedRef.current) return;
      const { qrcode, qrcode_img_content } = res.data;
      if (!qrcode_img_content) {
        qrcodeRef.current = null;
        setPhase('failed');
        const msg = '未获取到二维码，请重新获取';
        setErrorText(msg);
        message.error(msg);
        return;
      }
      qrcodeRef.current = qrcode;
      setQrUrl(qrcode_img_content);
      setPhase('polling');
      scheduleNextPoll();
    } catch (err) {
      if (!mountedRef.current) return;
      setPhase('failed');
      const msg = extractErrorMessage(err, '获取二维码失败，请稍后重试');
      setErrorText(msg);
      message.error(msg);
    }
  }

  return (
    <div className="weixin-qr-bind">
      <p className="credentials-section-label">微信扫码绑定</p>

      {phase === 'bound' ? (
        <Alert
          type="success"
          showIcon
          message="已绑定"
          description="该微信机器人已完成扫码绑定，可正常收发消息。"
        />
      ) : (
        <>
          {errorText && (
            <Alert
              type="error"
              showIcon
              message={errorText}
              style={{ marginBottom: 12 }}
            />
          )}

          {phase === 'loading' && (
            <div className="weixin-qr-frame weixin-qr-frame--loading">
              <Spin />
            </div>
          )}

          {phase === 'polling' && qrUrl && (
            <div className="weixin-qr-frame">
              <QRCode value={qrUrl} size={QR_SIZE} />
              <div className="weixin-qr-hint">
                <Spin size="small" />
                <span>请使用微信扫码并确认登录…</span>
              </div>
            </div>
          )}

          <Button
            onClick={handleStart}
            loading={phase === 'loading'}
            style={{ marginTop: phase === 'idle' ? 0 : 12 }}
          >
            {phase === 'idle' ? '获取二维码' : '重新获取二维码'}
          </Button>
        </>
      )}
    </div>
  );
};

export default WeixinQrBind;
