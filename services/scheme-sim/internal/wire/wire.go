// Package wire frames ISO 8583 messages on a TCP stream: a 2-byte big-endian length, then the message
// (the common framing on card-network links). Messages use the ISO 8583:1987 ASCII spec.
package wire

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"

	"github.com/moov-io/iso8583"
	"github.com/moov-io/iso8583/specs"
)

// Spec is the message layout both ends use.
var Spec = specs.Spec87ASCII

// MaxLen bounds one message, so a bad length header can't make us allocate without limit.
const MaxLen = 8192

// Read reads one framed message.
func Read(r io.Reader) (*iso8583.Message, error) {
	var hdr [2]byte
	if _, err := io.ReadFull(r, hdr[:]); err != nil {
		return nil, err
	}
	n := int(binary.BigEndian.Uint16(hdr[:]))
	if n == 0 || n > MaxLen {
		return nil, fmt.Errorf("frame length %d out of range", n)
	}
	buf := make([]byte, n)
	if _, err := io.ReadFull(r, buf); err != nil {
		return nil, err
	}
	m := iso8583.NewMessage(Spec)
	if err := m.Unpack(buf); err != nil {
		return nil, fmt.Errorf("unpack: %w", err)
	}
	return m, nil
}

// Write packs and writes one framed message.
func Write(w io.Writer, m *iso8583.Message) error {
	b, err := m.Pack()
	if err != nil {
		return fmt.Errorf("pack: %w", err)
	}
	if len(b) > MaxLen {
		return errors.New("message too long")
	}
	out := make([]byte, 2+len(b))
	binary.BigEndian.PutUint16(out, uint16(len(b)))
	copy(out[2:], b)
	_, err = w.Write(out)
	return err
}

// Get returns a field's string value, or "" when absent.
func Get(m *iso8583.Message, id int) string {
	v, err := m.GetString(id)
	if err != nil {
		return ""
	}
	return v
}
