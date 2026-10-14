package com.tokenledgercloud.api.domain.accounting.ledger;

import java.util.Objects;
import java.util.Optional;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.tokenledgercloud.api.domain.usage.entity.UsageLog;
import com.tokenledgercloud.api.global.exception.ApiException;
import com.tokenledgercloud.api.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 이벤트를 원장에 한 번만 쌓는 진입점.
 * 같은 이벤트가 다시 오면 새 행을 만들지 않고, 본문이 다르거나 다른 이벤트가 같은 requestId를 쓰면 충돌로 거절한다.
 * 일부러 @Transactional을 붙이지 않았다. 조회와 저장이 각자 짧은 트랜잭션을 쓰게 해서
 * 한 요청이 DB 연결을 둘 잡지 않도록 한다. (UsageLogService와 같은 방식)
 */
// 스프링 서비스 빈으로 등록한다.
@Service
// final 필드를 받는 생성자를 롬복이 만들어 준다.
@RequiredArgsConstructor
public class AccountingLedgerWriter {

	// 트랜잭션 단위 작업을 맡는 빈.
	private final AccountingLedgerTx ledgerTx;

	/**
	 * 이벤트를 기록한다.
	 *
	 * @throws ApiException 같은 eventId에 다른 본문이면 IDEMPOTENCY_CONFLICT,
	 *                      다른 eventId가 같은 requestId를 쓰면 IDEMPOTENCY_CONFLICT,
	 *                      동시 요청을 풀지 못했으면 INGESTION_RETRY_LATER
	 */
	public AccountingLedgerResult record(AccountingLedgerCommand command) {
		// 1. 먼저 같은 이벤트가 이미 있는지 확인한다.
		Optional<UsageLog> existing = ledgerTx.findEvent(
				command.projectId(), command.environment(), command.eventId());
		if (existing.isPresent()) {
			return resolveDuplicate(existing.get(), command);
		}
		try {
			// 2. 없으면 이벤트와 원장 값을 한 트랜잭션에 저장한다.
			String usageEventId = ledgerTx.insertEventWithValue(command);
			return new AccountingLedgerResult(usageEventId, false);
		} catch (ConcurrencyFailureException exception) {
			// 교착 상태나 잠금 대기 초과: 아무것도 저장되지 않았으므로 같은 요청을 다시 보내도 안전하다.
			throw new ApiException(ErrorCode.INGESTION_RETRY_LATER);
		} catch (DataIntegrityViolationException exception) {
			// 3. 동시에 들어온 같은 이벤트가 먼저 커밋됐다면 UNIQUE 제약 때문에 여기로 온다.
			//    새 트랜잭션에서 다시 읽어 중복으로 처리한다.
			Optional<UsageLog> raced = ledgerTx.findEvent(
					command.projectId(), command.environment(), command.eventId());
			if (raced.isPresent()) {
				return resolveDuplicate(raced.get(), command);
			}
			// 4. eventId로는 못 찾았는데 requestId로 찾아지면, 다른 이벤트가 같은 requestId를 쓴 것이다.
			if (command.requestId() != null && !command.requestId().isBlank()
					&& ledgerTx.findEventByRequestId(
							command.projectId(), command.environment(), command.requestId()).isPresent()) {
				throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT);
			}
			// 5. UNIQUE 위반인데 맞는 행이 없다면 경쟁 상대가 사라진 것이므로 다시 시도하면 된다.
			if (isUniqueViolation(exception)) {
				throw new ApiException(ErrorCode.INGESTION_RETRY_LATER);
			}
			// 그 밖의 무결성 오류는 그대로 던진다.
			throw exception;
		}
	}

	// 이미 저장된 이벤트와 새 요청을 비교해서, 같으면 중복 결과를, 다르면 충돌 예외를 낸다.
	private AccountingLedgerResult resolveDuplicate(UsageLog stored, AccountingLedgerCommand command) {
		// 본문 지문이 다르면 같은 eventId로 다른 내용을 보낸 것이므로 거절한다.
		if (!Objects.equals(stored.getPayloadFingerprint(), command.payloadFingerprint())) {
			throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT);
		}
		// 같은 본문이면 원장에 아무것도 더하지 않고 기존 이벤트 id를 돌려준다.
		return new AccountingLedgerResult(stored.getId(), true);
	}

	// 예외의 원인 사슬에 UNIQUE 제약 위반이 있는지 확인한다.
	private boolean isUniqueViolation(DataIntegrityViolationException exception) {
		for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException violation
					&& violation.getKind() == ConstraintViolationException.ConstraintKind.UNIQUE) {
				return true;
			}
		}
		return false;
	}
}
