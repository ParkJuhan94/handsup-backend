package dev.handsup.common.exception;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import lombok.extern.slf4j.Slf4j;

import dev.handsup.common.redisson.LockAcquisitionException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final String ARGUMENT_NOT_VALID_MESSAGE = "잘못된 입력 값 입니다";
	private static final String ARGUMENT_NOT_VALID_ERROR_CODE = "G_001";

	@ResponseStatus(HttpStatus.BAD_REQUEST)
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ErrorResponseTemplate handleMethodArgumentNotValidException(
		MethodArgumentNotValidException e
	) {
		log.error("MethodArgumentNotValidException : ", e);

		String message = ARGUMENT_NOT_VALID_MESSAGE;
		List<ObjectError> allErrors = e.getBindingResult().getAllErrors();
		if (!allErrors.isEmpty()) {
			message = allErrors.get(0).getDefaultMessage();
		}

		return new ErrorResponseTemplate(message, ARGUMENT_NOT_VALID_ERROR_CODE);
	}

	// 락 대기 시간 초과는 동시 요청이 몰린 상황이라 클라이언트가 잠시 후 다시 시도하면 된다(ValidationException보다 먼저 매칭된다)
	@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
	@ExceptionHandler(LockAcquisitionException.class)
	public ErrorResponseTemplate handleLockAcquisitionException(LockAcquisitionException e) {
		log.warn("LockAcquisitionException : {}", e.getMessage());
		return new ErrorResponseTemplate(e.getMessage(), e.getCode());
	}

	@ResponseStatus(HttpStatus.BAD_REQUEST)
	@ExceptionHandler(ValidationException.class)
	public ErrorResponseTemplate handleValidationException(ValidationException e) {
		log.error("ValidationException : ", e);
		return new ErrorResponseTemplate(e.getMessage(), e.getCode());
	}

	@ResponseStatus(HttpStatus.BAD_REQUEST)
	@ExceptionHandler(NotFoundException.class)
	public ErrorResponseTemplate handleNotFoundException(NotFoundException e) {
		log.error("NotFoundException : ", e);
		return new ErrorResponseTemplate(e.getMessage(), e.getCode());
	}
}
