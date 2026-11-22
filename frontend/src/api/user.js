import request from './request'

export const listUsers = (page, size) => request.get('/users', { params: { page, size } })
export const getUser = (id) => request.get(`/users/${id}`)
export const createUser = (data) => request.post('/users', data)
export const updateUser = (id, data) => request.put(`/users/${id}`, data)
export const deleteUser = (id) => request.delete(`/users/${id}`)
export const getDatasource = () => request.get('/users/ping/datasource')