/**
 * repository — Storage abstraction. TaskRepository is always an interface.
 * The service layer depends only on this interface; concrete implementations
 * (in-memory, JPA) live here but are never referenced by name above this layer.
 */
package com.poc.taskengine.repository;
