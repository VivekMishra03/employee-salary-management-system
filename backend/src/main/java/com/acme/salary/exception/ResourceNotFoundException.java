package com.acme.salary.exception;

/** HTTP 404. Names the resource and id, never anything else about the data. */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String resource, Long id) {
        super(resource + " " + id + " was not found");
    }
}
