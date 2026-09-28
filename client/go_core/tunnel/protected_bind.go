package tunnel

import (
	"github.com/amnezia-vpn/amneziawg-go/v3/conn"
)

type protectedStdBind struct {
	inner     *conn.StdNetBind
	protector SocketProtector
}

func newProtectedStdBind(p SocketProtector) conn.Bind {
	inner := conn.NewStdNetBind().(*conn.StdNetBind)
	return &protectedStdBind{inner: inner, protector: p}
}

func (b *protectedStdBind) Open(port uint16) ([]conn.ReceiveFunc, uint16, error) {
	fns, actual, err := b.inner.Open(port)
	if err != nil {
		return nil, 0, err
	}
	if b.protector != nil {
		if fd, e := b.inner.PeekLookAtSocketFd4(); e == nil {
			b.protector.Protect(int32(fd))
		}
		if fd, e := b.inner.PeekLookAtSocketFd6(); e == nil {
			b.protector.Protect(int32(fd))
		}
	}
	return fns, actual, nil
}

func (b *protectedStdBind) Close() error { return b.inner.Close() }
func (b *protectedStdBind) SetMark(mark uint32) error {
	return b.inner.SetMark(mark)
}
func (b *protectedStdBind) Send(bufs [][]byte, ep conn.Endpoint) error {
	return b.inner.Send(bufs, ep)
}
func (b *protectedStdBind) ParseEndpoint(s string) (conn.Endpoint, error) {
	return b.inner.ParseEndpoint(s)
}
func (b *protectedStdBind) BatchSize() int { return b.inner.BatchSize() }
