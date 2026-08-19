package com.ddd.d3.community.application;

/** Raised when a comment deletion targets a comment the requester does not own or that is absent. */
public final class CommentNotFoundException extends RuntimeException {}
