package dev.handsup.common.redisson;

import dev.handsup.common.exception.ValidationException;

// 락 대기 시간 안에 락을 얻지 못한 경우. 요청이 잘못된 것이 아니라 동시 요청이 몰린 것이라 400이 아닌 429로 응답한다.
public class LockAcquisitionException extends ValidationException {

	public LockAcquisitionException() {
		super(LockErrorCode.FAILED_TO_GET_LOCK);
	}
}
