package grpcclient

import (
	"net/http"

	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

type APIError struct {
	HTTPStatus int    `json:"-"`
	Code       string `json:"code"`
	Message    string `json:"message"`
}

func (e *APIError) Error() string {
	return e.Message
}

// GRPCToHTTPError converts a gRPC error to an API error with appropriate HTTP status
func GRPCToHTTPError(err error) *APIError {
	if err == nil {
		return nil
	}

	st, ok := status.FromError(err)
	if !ok {
		return &APIError{
			HTTPStatus: http.StatusInternalServerError,
			Code:       "INTERNAL_ERROR",
			Message:    "Internal server error",
		}
	}

	switch st.Code() {
	case codes.OK:
		return nil

	case codes.InvalidArgument:
		return &APIError{
			HTTPStatus: http.StatusBadRequest,
			Code:       "INVALID_ARGUMENT",
			Message:    st.Message(),
		}

	case codes.NotFound:
		return &APIError{
			HTTPStatus: http.StatusNotFound,
			Code:       "NOT_FOUND",
			Message:    st.Message(),
		}

	case codes.AlreadyExists:
		return &APIError{
			HTTPStatus: http.StatusConflict,
			Code:       "ALREADY_EXISTS",
			Message:    st.Message(),
		}

	case codes.FailedPrecondition:
		return &APIError{
			HTTPStatus: http.StatusUnprocessableEntity,
			Code:       "FAILED_PRECONDITION",
			Message:    st.Message(),
		}

	case codes.Unauthenticated:
		return &APIError{
			HTTPStatus: http.StatusUnauthorized,
			Code:       "UNAUTHENTICATED",
			Message:    st.Message(),
		}

	case codes.PermissionDenied:
		return &APIError{
			HTTPStatus: http.StatusForbidden,
			Code:       "PERMISSION_DENIED",
			Message:    st.Message(),
		}

	case codes.ResourceExhausted:
		return &APIError{
			HTTPStatus: http.StatusTooManyRequests,
			Code:       "RESOURCE_EXHAUSTED",
			Message:    st.Message(),
		}

	case codes.Unavailable:
		return &APIError{
			HTTPStatus: http.StatusServiceUnavailable,
			Code:       "SERVICE_UNAVAILABLE",
			Message:    "Service temporarily unavailable",
		}

	case codes.DeadlineExceeded:
		return &APIError{
			HTTPStatus: http.StatusGatewayTimeout,
			Code:       "DEADLINE_EXCEEDED",
			Message:    "Request timeout",
		}

	case codes.Internal:
		return &APIError{
			HTTPStatus: http.StatusInternalServerError,
			Code:       "INTERNAL_ERROR",
			Message:    "Internal server error",
		}

	default:
		return &APIError{
			HTTPStatus: http.StatusInternalServerError,
			Code:       "UNKNOWN_ERROR",
			Message:    "An unexpected error occurred",
		}
	}
}
